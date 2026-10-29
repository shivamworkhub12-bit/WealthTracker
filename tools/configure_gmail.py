#!/usr/bin/env python3
"""Configure WealthOS's own Gmail OAuth client without exposing its JSON in chat or Git."""
import argparse
import json
import os
from pathlib import Path
import re
import tempfile

def private_write(path,content):
    descriptor,temporary=tempfile.mkstemp(prefix=".gmail-setup-",dir=path.parent)
    try:
        with os.fdopen(descriptor,"w") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary,path)
    finally:
        if os.path.exists(temporary):os.unlink(temporary)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--email",required=True)
    parser.add_argument("--credentials",type=Path)
    args=parser.parse_args()
    email=args.email.strip().lower()
    if not re.fullmatch(r"[^@\s=]+@[^@\s=]+\.[^@\s=]+",email):parser.error("Provide the finance Gmail address to authorize")
    directory=Path.home()/".config"/"wealthos"
    directory.mkdir(parents=True,exist_ok=True,mode=0o700)
    if args.credentials:
        raw=args.credentials.expanduser().read_text()
        if len(raw)>32768:parser.error("Credentials file is too large")
        credentials=json.loads(raw).get("installed",{})
        if not str(credentials.get("client_id","")).endswith(".apps.googleusercontent.com") or not credentials.get("client_secret"):
            parser.error("Download credentials for an OAuth Desktop app")
        private_write(directory/"google-oauth-client.json",raw)
        print("Desktop OAuth credentials saved with owner-only access. Contents were not printed.")
    properties=directory/"gmail.properties"
    lines=properties.read_text().splitlines() if properties.exists() else []
    lines=[line for line in lines if not line.strip().startswith("wealthos.gmail.expected-email=")]
    lines.append("wealthos.gmail.expected-email="+email)
    if not any(line.strip().startswith("wealthos.gmail.auto-record=") for line in lines):lines.append("wealthos.gmail.auto-record=true")
    private_write(properties,"\n".join(lines)+"\n")
    print("Finance account configured. Restart the backend if the expected email changed.")
    print("After adding credentials, use Connect Gmail inside WealthOS to authorize Google's read-only scope.")

if __name__=="__main__":main()
