# Connect Gmail directly to WealthOS

This is the application's own Gmail integration. It does not use the Gmail connection in the chat. The backend requests only `https://www.googleapis.com/auth/gmail.readonly`, verifies the selected Gmail address against your private configuration, and polls Gmail every minute while the backend is running.

## 1. Create Google OAuth credentials

In a normal browser such as Chrome or Safari:

1. Open [Google Cloud Console](https://console.cloud.google.com/apis/library/gmail.googleapis.com) and select or create a project for WealthOS.
2. Enable **Gmail API** for that project.
3. Open **Google Auth Platform**. Set the application name and your contact email. For a personal development setup, use the testing audience and add your finance Gmail address as a test user.
4. Under **Data Access**, add only the Gmail read-only scope shown above. Google's scope covers the mailbox; bank-only filtering is enforced by WealthOS rather than by the OAuth scope itself.
5. Under **Clients**, create a client with application type **Desktop app**. Download the JSON credentials. Do not paste the JSON contents or your Gmail password into a chat.

Google documents the [Desktop OAuth flow with PKCE](https://developers.google.com/identity/protocols/oauth2/native-app) and [Gmail's read-only scope](https://developers.google.com/workspace/gmail/api/auth/scopes). This integration uses the loopback callback `http://127.0.0.1:8080/api/gmail/oauth/callback`; it requires no public deployment. The local backend must continue to use port 8080 for this callback.

## 2. Configure the local app

From the project root, replace the file path and address below:

```bash
python3 tools/configure_gmail.py --email YOUR_FINANCE_EMAIL --credentials /absolute/path/to/downloaded-client.json
```

The helper validates a Desktop client, copies the credentials to `~/.config/wealthos/google-oauth-client.json` with owner-only file permissions, and writes the expected address to `~/.config/wealthos/gmail.properties`. The file contents are not printed. Restart the backend after changing the expected email. If only the credentials JSON was added, refreshing Gmail status is sufficient.

The app reads only Google HTTPS endpoints; endpoints provided inside a downloaded credentials file cannot redirect tokens somewhere else. Both the API and the database are bound to the local machine.

## 3. Authorize in WealthOS

1. Open <http://localhost:3000> in Chrome or Safari. Google does not permit authorization in embedded app browsers; see [Google's secure-browser policy](https://developers.google.com/identity/protocols/oauth2/policies).
2. In **Gmail transaction tracking**, choose **New emails only**, **Last 7 days**, or **Last 30 days** for the first sync. The default does not import history.
3. Click **Connect Gmail** and sign in to the finance account shown in the panel.
4. Review Google's consent screen and grant read-only access if you want to proceed. WealthOS rejects another Google account or a grant that is not the requested read-only scope.
5. After returning to WealthOS, background syncing begins on the next one-minute tick. **Sync now** also queues a sync. Complete verified payments can record automatically; uncertain ones appear in **Detected bank payments** for review.

No Google API call can access mailbox messages until this authorization is completed. In Google's external testing mode, refresh tokens for this scope expire after seven days; WealthOS then shows **Reconnect needed**. See [Google's token-expiration documentation](https://developers.google.com/identity/protocols/oauth2#expiration).

## Matching alerts and handling duplicates

The initial sender-domain allowlist is `axisbank.com,idfcfirstbank.com,icicibank.com,sbicard.com`, including their subdomains. Gmail search combines those senders with transaction-related words and excludes spam, trash, sent mail, and drafts. Before fetching a body, the backend checks the actual From address against exact domain boundaries; a lookalike domain such as `icicibank.com.example.net` does not match.

Banks can change their sending domains, so verify your actual alert senders. You can add verified domains in `~/.config/wealthos/gmail.properties` and restart the backend:

```properties
wealthos.gmail.sender-domains=axisbank.com,idfcfirstbank.com,icicibank.com,sbicard.com
wealthos.gmail.auto-record=true
```

Set `auto-record=false` for review-only processing. Missing Gmail-server DMARC confirmation or truncated content also prevents automatic recording. Sender authentication alone is not enough: the parser must find an unambiguous completed INR payment with merchant, date, amount, masked account ending, and bank reference. Refunds, transfers, and ambiguous details require review.

Email message IDs are namespaced by mailbox. Bank-reference/account/direction fingerprints are shared with SMS capture. This prevents one payment being counted twice if it arrives through both channels while preserving separate purchases with different references. Similar manual entries require review. No raw email or attachment content is retained in the WealthOS database.

## Reliability and privacy

- Refresh tokens are encrypted with AES-256-GCM before entering PostgreSQL. The encryption key is an owner-only file at `~/.config/wealthos/gmail-token-key`. Access tokens stay in backend memory. Never commit these private files.
- The authorization flow uses PKCE, short-lived one-time state, and an HttpOnly browser-bound cookie. No token or authorization code is returned to frontend JavaScript.
- Background polling saves the time window and page cursor. Checkpoints advance only after messages have been acknowledged. Overlapping subsequent windows and a durable seen-message table handle indexing delays and retries without double-counting.
- Each round processes at most one page of 100 matching emails. If there are more results, the next round resumes the same saved window. Transient errors and rate limits use exponential backoff. Revoked or expired grants stop syncing until the user reconnects.
- Plain-text and HTML MIME bodies are supported. HTML parsing does not fetch images, links, scripts, or tracking pixels. Attachments are not downloaded. Long or unsupported email formats may need manual review; this parser is not guaranteed to cover every bank template.
- **Disconnect** stops local sync, removes the stored refresh token, and attempts to revoke the Google grant. Existing transactions and review records remain. If Google is unavailable during revocation, remove WealthOS separately in your Google Account's third-party access settings.

## Verification

`cd backend && mvn test` exercises account verification, PKCE/state checks, encrypted token storage, MIME extraction, sender boundaries, email/SMS duplicate detection, saved pagination, rate-limit backoff, and revoked-grant handling. Google responses are mocked and synthetic database records are rolled back. These tests do not authorize or access a real Gmail account. `cd frontend && npm run build` checks the connection UI.
