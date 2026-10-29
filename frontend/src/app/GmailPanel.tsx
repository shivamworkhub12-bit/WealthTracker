"use client";

import { useEffect, useState } from "react";
import { ExternalLink, Mail, RefreshCw, ShieldCheck } from "lucide-react";

type GmailStatus = { configured: boolean; connected: boolean; expectedEmail: string; email: string | null; state: string; message: string; lastSynced: string | null; retryAfter: string | null; senderDomains: string[]; autoRecord: boolean; emailsChecked: number; credentialsPath: string };
const callbacks: Record<string, string> = {
  connected: "Gmail is connected. New bank alerts will sync automatically while WealthOS is running.",
  denied: "Google authorization was cancelled. You can reconnect whenever you’re ready.",
  state: "This connection attempt could not be verified. Use Connect Gmail to start a fresh attempt.",
  expired: "The connection link expired. Start a new connection attempt.",
  account: "A different Google account was selected. Connect the finance account shown here.",
  scope: "The requested read-only permission was not granted. Start a new connection and check the consent screen.",
  refresh: "Google did not provide background access. Reconnect and approve offline read-only access.",
  failed: "Google could not finish connecting. Check that Gmail API is enabled and this account is a test user.",
};

export default function GmailPanel({ api }: { api: string }) {
  const [status, setStatus] = useState<GmailStatus | null>(null);
  const [error, setError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [lookback, setLookback] = useState(0);
  async function request(path: string, method = "GET", body?: unknown) {
    const response = await fetch(`${api}/gmail${path}`, { method, headers: body ? { "Content-Type": "application/json" } : undefined, body: body ? JSON.stringify(body) : undefined });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error ?? result.message ?? "The Gmail connection is unavailable. Check the local backend.");
    return result;
  }
  async function refresh() { try { setStatus(await request("/status")); setLoadError(""); } catch (e) { setLoadError(e instanceof Error ? e.message : "Could not load Gmail status."); } }
  useEffect(() => {
    const result = new URL(window.location.href).searchParams.get("gmail");
    if (result) { setNotice(callbacks[result] ?? callbacks.failed); const url = new URL(window.location.href); url.searchParams.delete("gmail"); window.history.replaceState({}, "", url); }
    void refresh(); const timer = setInterval(() => void refresh(), 15000); return () => clearInterval(timer);
  }, [api]);
  async function connect() {
    setError(""); setBusy(true);
    try { const result = await request("/connect", "POST", { lookbackDays: lookback }); window.location.assign(result.url); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not begin Google authorization."); setBusy(false); }
  }
  async function sync() {
    setBusy(true); setError("");
    try { await request("/sync", "POST"); setNotice("Sync queued. Detected payments will appear in the review queue or your transactions."); await refresh(); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not sync Gmail."); }
    finally { setBusy(false); }
  }
  async function disconnect() {
    setBusy(true); setError("");
    try { const result = await request("/disconnect", "POST"); setNotice(result.googleRevoked ? "Gmail access was disconnected and revoked. Existing transactions are kept." : "Local syncing is disconnected. Google could not confirm revocation; you can also remove WealthOS in your Google Account permissions."); await refresh(); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not disconnect Gmail."); }
    finally { setBusy(false); }
  }
  const syncing = status?.state === "SYNCING" || status?.state === "CATCHING_UP";
  return <section className="panel gmail-panel" id="gmail-tracking"><div className="panel-head capture-heading"><div className="capture-title"><span className="capture-icon gmail-icon"><Mail size={19}/></span><div><h2>Gmail transaction tracking</h2><p>{status?.email ?? status?.expectedEmail ?? "Your finance inbox"}</p></div></div><span className={`capture-state ${status?.connected && status.state === "CONNECTED" ? "connected" : ""}`}><i/>{!status ? "Checking connection" : !status.configured ? "Google setup needed" : status.state === "RECONNECT_REQUIRED" ? "Reconnect needed" : syncing ? "Syncing bank alerts" : status.connected ? status.state === "ERROR" ? "Needs attention" : "Connected" : "Not connected"}</span></div>
    <div className="capture-explainer"><ShieldCheck size={16}/><span>Google’s read-only permission covers your mailbox. WealthOS filters approved bank senders, stores parsed payment details locally, and never sends, deletes, or marks emails as read.</span></div>
    <p className="gmail-status-message">{status?.message ?? "Loading your app’s Gmail connection…"}</p>
    <p className="gmail-status-message">Use Chrome or Safari for Google authorization; Google does not support signing in through embedded app browsers.</p>
    {(error || loadError) && <p className="capture-error" role="alert">{error || loadError}</p>}{notice && <p className="gmail-notice" role="status">{notice}</p>}
    <div className="gmail-actions">{!status?.connected || status.state === "RECONNECT_REQUIRED" ? <><label className="gmail-lookback">First sync<select aria-label="Emails to include on first Gmail sync" value={lookback} onChange={e => setLookback(Number(e.target.value))}><option value={0}>New emails only</option><option value={7}>Last 7 days</option><option value={30}>Last 30 days</option></select></label><button className="button primary" disabled={!status?.configured || busy} onClick={() => void connect()}><Mail size={15}/>{busy ? "Opening Google…" : status?.connected ? "Reconnect Gmail" : "Connect Gmail"}</button></> : <><button className="button primary" disabled={busy || syncing} onClick={() => void sync()}><RefreshCw size={15}/>{syncing ? "Syncing…" : "Sync now"}</button><button className="button secondary" disabled={busy} onClick={() => void disconnect()}>Disconnect</button></>}<button className="icon-button" aria-label="Refresh Gmail connection status" onClick={() => { setError(""); void refresh(); }}><RefreshCw size={14}/></button></div>
    {status?.connected && <div className="gmail-sync-details"><span>Last successful sync: {status.lastSynced ? new Date(status.lastSynced).toLocaleString("en-IN") : "Waiting for first sync"}</span><span>{status.emailsChecked} matching emails checked · Automatic checks every minute</span>{status.retryAfter && status.state === "ERROR" && <span>Next retry: {new Date(status.retryAfter).toLocaleTimeString("en-IN")}</span>}</div>}
    <details className="capture-setup gmail-setup" open={status != null && !status.configured}><summary>Set up Google access for WealthOS</summary><ol><li>Open <a href="https://console.cloud.google.com/apis/library/gmail.googleapis.com" target="_blank" rel="noreferrer">Google Cloud Console <ExternalLink size={10}/></a>, select or create a WealthOS project, and enable Gmail API.</li><li>In Google Auth Platform, configure the app name and contact email. For personal testing, add your finance email as a test user.</li><li>Add only the Gmail read-only scope, then create an OAuth client with application type <strong>Desktop app</strong>.</li><li>Download its JSON credentials and place the file at <code>{status?.credentialsPath ?? "~/.config/wealthos/google-oauth-client.json"}</code>. Keep it private; the app does not need your Gmail password.</li><li>Refresh this panel, choose the first-sync period, then use <strong>Connect Gmail</strong> and authorize the finance account on Google’s page.</li></ol><p>Google testing-mode authorizations can expire and require reconnecting. This local app syncs while the Mac and backend are running. A detailed setup guide is included in the project’s <code>docs/gmail-setup.md</code>.</p></details>
    {status?.senderDomains && <div className="gmail-senders">Bank email domains: {status.senderDomains.join(", ")}. Messages from other domains are excluded; add verified bank domains in the local Gmail settings if needed.</div>}
  </section>;
}
