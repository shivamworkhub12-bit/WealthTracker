#!/usr/bin/env python3
"""Opt-in local bank-SMS collector. Does not open Messages until explicitly enabled."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

CONFIG_DIR = Path.home() / ".config" / "wealthos"
APPLE_EPOCH = dt.datetime(2001, 1, 1, tzinfo=dt.timezone.utc)
UTC = dt.timezone.utc


def normalize_sender(sender):
    # Match the same bank identifier across carrier routing prefixes (e.g. VM-).
    return re.sub(r"^[A-Z]{2}-", "", sender.strip().upper())


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    fd = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as handle:
        json.dump(value, handle, indent=2)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def setup(args):
    if not args.consent:
        raise ValueError("Setup requires --consent after approving local access to bank SMS messages.")
    senders = sorted({normalize_sender(s) for s in args.senders.split(",") if s.strip()})
    if not senders or any(not re.fullmatch(r"[A-Z0-9+-]{3,100}", s) for s in senders):
        raise ValueError("Provide an explicit comma-separated bank sender allowlist.")
    directory = Path(args.config).parent
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    token_path = directory / "capture-token"
    if not token_path.exists():
        fd = os.open(str(token_path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as handle:
            handle.write(secrets.token_urlsafe(48))
    now = dt.datetime.now(UTC).isoformat()
    config = {"enabled": True, "approvedAt": now, "senders": senders, "apiUrl": "http://127.0.0.1:8080/api/capture",
              "messagesDb": str(Path.home() / "Library" / "Messages" / "chat.db"),
              "tokenFile": str(token_path), "stateFile": str(directory / "collector-state.json"),
              "lookbackDays": args.lookback_days, "pollSeconds": 30}
    atomic_json(args.config, config)
    properties = directory / "capture.properties"
    fd = os.open(str(properties), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as handle:
        handle.write("wealthos.capture.allowed-senders=" + ",".join(senders) + "\n")
        handle.write("wealthos.capture.token-file=" + str(token_path) + "\n")
        handle.write("wealthos.capture.auto-record=" + str(args.auto_record).lower() + "\n")
    print("Local capture configured. No Messages data has been read.")
    print("Restart the WealthOS backend, then run this tool with run --once or run --watch.")
    print("Automatic recording:", "confident completed payments only" if args.auto_record else "off; all candidates await review")


def load_config(path):
    if not Path(path).is_file():
        raise ValueError("Capture is not configured. Run setup only after approving Messages access.")
    config = json.loads(Path(path).read_text())
    if config.get("enabled") is not True or not config.get("approvedAt") or not config.get("senders"):
        raise ValueError("Capture is disabled or has no approved bank senders.")
    url = urllib.parse.urlparse(config["apiUrl"])
    if url.scheme != "http" or url.hostname not in {"127.0.0.1", "localhost", "::1"} or url.username or url.password:
        raise ValueError("This collector only sends alerts to the local WealthOS API.")
    config["senders"] = sorted({normalize_sender(s) for s in config["senders"]})
    return config


def post(config, route, value):
    token = Path(config["tokenFile"]).read_text().strip()
    if len(token) < 32:
        raise ValueError("Collector token is invalid.")
    request = urllib.request.Request(config["apiUrl"].rstrip("/") + route, data=json.dumps(value).encode(), method="POST",
                                     headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    # Never follow redirects with the collector token or SMS content.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(request, timeout=15) as response:
        body = response.read()
        return json.loads(body) if body else None


def heartbeat(config, state, message):
    try:
        post(config, "/heartbeat", {"state": state, "message": message})
    except (OSError, ValueError, urllib.error.URLError):
        pass


def read_rows(connection, config, state):
    """Query only incoming SMS from approved senders. Does not scan personal message bodies."""
    connection.create_function("wealthos_sender", 1, normalize_sender)
    columns = {row[1] for row in connection.execute("PRAGMA table_info(message)")}
    required = {"text", "handle_id", "is_from_me", "date"}
    if not required.issubset(columns):
        raise ValueError("The Messages database schema is unsupported; collection stopped.")
    fallback = "CAST(m.ROWID AS TEXT) || ':' || CAST(m.date AS TEXT)"
    source = f"COALESCE(NULLIF(m.guid,''), {fallback})" if "guid" in columns else fallback
    placeholders = ",".join("?" for _ in config["senders"])
    after = dt.datetime.fromisoformat(state["scanAfter"])
    cutoff = (after - APPLE_EPOCH).total_seconds()
    query = (f"SELECT m.ROWID, {source}, m.text, m.date, wealthos_sender(h.id) "
             "FROM message m JOIN handle h ON h.ROWID=m.handle_id "
             f"WHERE m.is_from_me=0 AND wealthos_sender(h.id) IN ({placeholders}) "
             "AND (CASE WHEN abs(m.date)>1000000000000 THEN m.date/1000000000.0 ELSE m.date END)>=? ")
    rows = list(connection.execute(query + "AND m.ROWID>? ORDER BY m.ROWID LIMIT 100", config["senders"] + [cutoff, state["lastRowId"]]))
    pending = state.get("pendingRows", [])
    if pending:
        retry = list(connection.execute(query + "AND m.ROWID IN (" + ",".join("?" for _ in pending) + ") ORDER BY m.ROWID", config["senders"] + [cutoff] + pending))
        rows = sorted({row[0]: row for row in retry + rows}.values())
    return rows


def run_once(config):
    state_path = Path(config["stateFile"])
    if state_path.exists():
        state = json.loads(state_path.read_text())
    else:
        approved = dt.datetime.fromisoformat(config.get("approvedAt", dt.datetime.now(UTC).isoformat()))
        after = approved - dt.timedelta(days=config.get("lookbackDays", 0))
        state = {"lastRowId": 0, "scanAfter": after.isoformat(), "pendingRows": []}
        atomic_json(state_path, state)
    # Opening a read-only URI also prevents accidentally creating a new chat.db.
    database = Path(config["messagesDb"]).expanduser().resolve()
    with sqlite3.connect(database.as_uri() + "?mode=ro", uri=True, timeout=5) as connection:
        connection.execute("PRAGMA query_only=ON")
        rows = read_rows(connection, config, state)
    imported = 0
    for row_id, source_id, text, timestamp, sender in rows:
        state["lastRowId"] = max(state["lastRowId"], row_id)
        if not text or not text.strip():
            if row_id not in state["pendingRows"]:
                state["pendingRows"].append(row_id)
            if len(state["pendingRows"]) > 500:
                raise ValueError("Too many bank messages have unavailable text. Collection paused for review.")
            atomic_json(state_path, state)
            continue
        seconds = timestamp / 1_000_000_000 if abs(timestamp) > 1_000_000_000_000 else timestamp
        received = APPLE_EPOCH + dt.timedelta(seconds=seconds)
        # On failure the cursor on disk remains at the last acknowledged event.
        # The backend's stable event key makes a retry after an ambiguous timeout safe.
        post(config, "/incoming", {"sourceId": str(source_id), "sender": sender, "text": text,
                                    "receivedAt": received.isoformat()})
        state["pendingRows"] = [p for p in state["pendingRows"] if p != row_id]
        atomic_json(state_path, state)
        imported += 1
    if state["pendingRows"]:
        heartbeat(config, "ERROR", "Some bank SMS text is unavailable in Messages. Those alerts remain queued for retry.")
    else:
        heartbeat(config, "RUNNING", "Checking approved bank SMS senders every 30 seconds.")
    return imported, len(state["pendingRows"])


def run(args):
    while True:
        config = load_config(args.config)
        try:
            imported, pending = run_once(config)
            print(f"Acknowledged {imported} alerts; {pending} await readable text.", flush=True)
        except (PermissionError, sqlite3.DatabaseError):
            heartbeat(config, "PERMISSION_REQUIRED", "Messages could not be read. Check macOS privacy permission and Messages availability.")
            print("Messages access is unavailable. Check Full Disk Access for the app running this collector.", file=sys.stderr, flush=True)
            if not args.watch:
                return 2
        except (OSError, ValueError, urllib.error.URLError) as error:
            heartbeat(config, "ERROR", "Collection paused; verify the local backend and collector configuration.")
            # Never print message text, bank identifiers, or authorization headers.
            print("Collection will retry. Error type:", type(error).__name__, file=sys.stderr, flush=True)
            if not args.watch:
                return 1
        if not args.watch:
            return 0
        time.sleep(max(10, int(config.get("pollSeconds", 30))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default=str(CONFIG_DIR / "collector.json"))
    sub = parser.add_subparsers(dest="command", required=True)
    setup_parser = sub.add_parser("setup", help="Configure capture after explicit consent; does not read Messages")
    setup_parser.add_argument("--consent", action="store_true")
    setup_parser.add_argument("--senders", required=True)
    setup_parser.add_argument("--auto-record", action="store_true")
    setup_parser.add_argument("--lookback-days", type=int, choices=range(0, 31), default=0)
    run_parser = sub.add_parser("run")
    modes = run_parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--once", action="store_true")
    modes.add_argument("--watch", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "setup":
            setup(args)
            return 0
        return run(args)
    except (ValueError, OSError):
        print("Collector setup is missing or invalid. Check the local setup guide. No message contents were logged.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
