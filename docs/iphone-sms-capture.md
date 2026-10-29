# Local iPhone SMS capture

WealthOS can collect approved bank SMS messages after they sync to Messages on your Mac. It does not connect to a bank, Gmail, or a payment app, and does not read the iPhone directly. Text Message Forwarding uses your existing Apple Account; see [Apple's setup instructions](https://support.apple.com/en-au/102545).

## Consent and scope

The collector remains disabled until you approve access and configure an explicit sender allowlist. It opens the Mac Messages SQLite database in read-only mode, selects incoming messages from the approved bank IDs, and sends those alerts to the WealthOS API on `127.0.0.1`. Carrier prefixes such as `VM-` are removed when matching a sender. The collector does not send messages or modify the Messages database.

Messages is protected by macOS. Depending on the app that starts the collector, macOS may require Full Disk Access. That OS permission is broader than bank SMS access; only grant it after understanding and approving this scope. WealthOS itself queries the approved bank senders only. [Apple explains Full Disk Access here](https://support.apple.com/en-au/guide/mac-help/mchl211c911f/mac).

Only parsed fields, masked account endings, bank reference, sender, timestamp, and processing notes are persisted. Raw SMS text and OTP values are not retained in the WealthOS database or collector logs. The local configuration, token, and retry cursor live in `~/.config/wealthos/` with owner-only file permissions. They must never be committed to Git.

## Enable after approval

From the project root, replace the placeholder with the exact approved sender IDs:

```bash
python3 tools/sms_collector.py setup --consent --senders "YOUR_BANK_SENDER_1,YOUR_BANK_SENDER_2" --auto-record
```

Setup creates configuration only; it does not read Messages. Omit `--auto-record` to send every detected payment to the review queue instead. The default is to collect messages dated after setup was approved. An optional `--lookback-days 1` to `30` explicitly opts into that many days of earlier bank alerts.

Restart the backend so it loads `~/.config/wealthos/capture.properties`. Then run:

```bash
python3 tools/sms_collector.py run --once
python3 tools/sms_collector.py run --watch
```

The watch process checks every 30 seconds. Keep that process running; it is not installed as a system background service. Press Ctrl+C to stop. To disable capture persistently, set `enabled` to `false` in `~/.config/wealthos/collector.json` and stop the process. Removing `--auto-record` using setup and restarting the backend switches to review-only mode.

## Recording rules

- Completed INR debit/credit alerts with one clear amount, explicit valid date, merchant/sender, account/card ending, and bank reference can record automatically. Other payment candidates require review.
- OTPs, failed/declined/pending payments, payment requests, and bill reminders do not create transactions. Refunds and transfers require review because they should not be blindly counted as new income or expenses.
- A stable Messages event identifier prevents reprocessing a retry. A reference/account/direction fingerprint catches the same bank transaction arriving in another alert. Two otherwise identical purchases with different bank references remain distinct.
- Similar manual entries are sent for review. The review form requires an explicit separate-payment confirmation when a similar entry exists. Conflicting amounts or dates for the same bank reference cannot silently overwrite a recorded transaction.
- Checkpoints advance only after acknowledgement. If the API is offline, the collector retries the same event after recovery. Raw alert text is never written to its cursor file.
- Some macOS versions store message contents only in an attributed-body archive. This first collector does not decode those archives. Such bank rows stay in a retry queue and the dashboard shows an attention state; they are not silently treated as recorded.

## What remains to validate with your messages

The parser and collector have synthetic tests, including PostgreSQL integration tests. Bank templates vary, so the first actual alerts need to be checked in **Automatic SMS capture → Recent alerts**. If a template cannot be parsed confidently, use the review form or preview a bank alert with account digits and references masked. No automatic parser can guarantee coverage of every bank or every payment alert.

Your Mac must be awake, Messages must sync the iPhone SMS, and Docker, PostgreSQL, the API, and the collector must be running. This is a local capture setup, not an always-online service. After a pause, it can catch up on approved messages that synced within its configured collection window.

## Checks

```bash
cd backend
mvn test
```

The integration tests use local PostgreSQL and roll back their synthetic records. From the project root, `python3 -m unittest discover -s tools -p 'test_*.py' -v` tests sender filtering, consent enforcement, failed-delivery retry, and unavailable-body retry using temporary synthetic SQLite databases. These tests never open your real Messages database.
