"use client";

import { useEffect, useRef, useState } from "react";
import { Check, ChevronDown, MessageSquare, RefreshCw, ShieldCheck, X } from "lucide-react";

type Category = { id: string; name: string; kind: string };
type CaptureStatus = { configured: boolean; autoRecord: boolean; collectorState: string; lastSeen: string | null; message: string; allowedSenders: string[]; review: number; recorded: number; duplicates: number; ignored: number };
type Alert = { id: string; sender: string; source: string; status: string; date: string | null; description: string | null; amount: number | null; type: string | null; account: string | null; reference: string | null; paymentMethod: string | null; suggestedCategory: string | null; notes: string[]; transactionId: string | null };
type Preview = Omit<Alert, "id" | "status" | "sender" | "source" | "suggestedCategory" | "transactionId"> & { category: string | null; ignored: boolean; autoEligible: boolean };
const money = (value: number | null) => value == null ? "Check amount" : new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(value);
const labels: Record<string, string> = { REVIEW: "Needs review", RECORDED: "Recorded", DUPLICATE: "Duplicate skipped", IGNORED: "Ignored" };

export default function CapturePanel({ api, categories, onRecorded }: { api: string; categories: Category[]; onRecorded: () => void }) {
  const [status, setStatus] = useState<CaptureStatus | null>(null);
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [showTest, setShowTest] = useState(false);
  const [text, setText] = useState("");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [loadError, setLoadError] = useState("");
  const [selected, setSelected] = useState<Alert | null>(null);
  const [filter, setFilter] = useState("REVIEW");
  const recorded = useRef<number | null>(null);
  const recordedCallback = useRef(onRecorded);
  recordedCallback.current = onRecorded;

  async function request(path: string, method = "GET", body?: unknown) {
    const response = await fetch(`${api}/capture${path}`, { method, headers: body ? { "Content-Type": "application/json" } : undefined, body: body ? JSON.stringify(body) : undefined });
    const result = await response.json().catch(() => null);
    if (!response.ok) throw new Error(result?.error ?? result?.message ?? "Could not load SMS capture. Check that the backend is running.");
    return result;
  }
  async function refresh() {
    try {
      const [state, incoming] = await Promise.all([request("/status"), request("/alerts")]);
      setStatus(state); setAlerts(incoming);
      setLoadError("");
      if (recorded.current !== null && state.recorded !== recorded.current) recordedCallback.current();
      recorded.current = state.recorded;
    } catch (e) { setLoadError(e instanceof Error ? e.message : "Capture is unavailable."); }
  }
  useEffect(() => { void refresh(); const timer = setInterval(() => void refresh(), 15000); return () => clearInterval(timer); }, [api]);

  async function parse() {
    setBusy(true); setError("");
    try { setPreview(await request("/preview", "POST", { text })); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not parse this alert."); }
    finally { setBusy(false); }
  }
  async function addToReview() {
    setBusy(true); setError("");
    try { await request("/alerts", "POST", { text, sender: "Pasted alert" }); setPreview(null); setText(""); setShowTest(false); setFilter("REVIEW"); await refresh(); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not add the alert."); }
    finally { setBusy(false); }
  }
  async function dismiss(alert: Alert) {
    setBusy(true); setError("");
    try { await request(`/alerts/${alert.id}/ignore`, "POST"); await refresh(); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not dismiss the alert."); }
    finally { setBusy(false); }
  }
  async function accept(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!selected) return;
    const data = new FormData(event.currentTarget); setBusy(true); setError("");
    const transaction = { date: data.get("date"), description: data.get("description"), amount: Number(data.get("amount")), type: data.get("type"), categoryId: data.get("categoryId") || null, account: data.get("account") || null, reference: data.get("reference") || null, paymentMethod: selected.paymentMethod, source: "SMS" };
    try { await request(`/alerts/${selected.id}/accept`, "POST", { transaction, separatePayment: data.get("separatePayment") === "on" }); setSelected(null); await refresh(); recordedCallback.current(); }
    catch (e) { setError(e instanceof Error ? e.message : "Could not record the transaction."); }
    finally { setBusy(false); }
  }

  const online = status?.collectorState === "RUNNING";
  const entries = (filter === "ALL" ? alerts : alerts.filter(a => a.status === filter)).slice(0, 8);
  return <section className="panel capture-panel" id="auto-capture">
    <div className="panel-head capture-heading"><div className="capture-title"><span className="capture-icon"><MessageSquare size={19}/></span><div><h2>Automatic SMS capture</h2><p>Your iPhone’s bank alerts, collected locally on this Mac.</p></div></div><span className={`capture-state ${online ? "connected" : ""}`}><i/>{online ? "Collector running" : status?.collectorState === "PERMISSION_REQUIRED" ? "Permission needed" : status?.collectorState === "ERROR" ? "Needs attention" : status?.collectorState === "OFFLINE" ? "Collector offline" : "Not connected"}</span></div>
    <div className="capture-explainer"><ShieldCheck size={16}/><span>{status?.configured ? `${status.allowedSenders.length} approved senders · ${status.autoRecord ? "Confident payments record automatically; uncertain alerts need review." : "Review mode: approve each detected payment before it counts."}` : "Connect approved bank senders to start. You can try the parser below without connecting Messages."}</span></div>
    {status && status.collectorState !== "NOT_CONNECTED" && !online && <p className="capture-attention">{status.message}</p>}
    <div className="capture-counts"><div><b>{status?.review ?? 0}</b><span>Need review</span></div><div><b>{status?.recorded ?? 0}</b><span>Recorded</span></div><div><b>{status?.duplicates ?? 0}</b><span>Duplicates skipped</span></div><div><b>{status?.ignored ?? 0}</b><span>Ignored</span></div></div>
    <div className="capture-toolbar"><div className="capture-tabs"><button className={filter === "REVIEW" ? "selected" : ""} onClick={() => setFilter("REVIEW")}>Review queue</button><button className={filter === "ALL" ? "selected" : ""} onClick={() => setFilter("ALL")}>Recent alerts</button></div><div className="capture-buttons"><button className="text-button" onClick={() => { setShowTest(!showTest); setError(""); }}>Try an alert <ChevronDown size={13}/></button><button className="icon-button" aria-label="Refresh SMS capture" onClick={() => void refresh()}><RefreshCw size={14}/></button></div></div>
    {(error || loadError) && !selected && <p className="capture-error" role="alert">{error || loadError}</p>}
    {showTest && <div className="capture-test"><label htmlFor="alert-text">Paste a bank SMS to check the extracted details</label><p>Only parsed fields are saved when you add an alert to review. Preview alone saves nothing.</p><textarea id="alert-text" value={text} maxLength={6000} placeholder="Paste a debit or credit alert here…" onChange={e => { setText(e.target.value); setPreview(null); }}/><div className="capture-test-buttons"><button className="text-button" onClick={() => { const d = new Date().toLocaleDateString("en-GB", { timeZone: "Asia/Kolkata" }).replaceAll("/", "-"); setText(`Rs.250.50 debited from A/c XX1234 to SWIGGY on ${d} via UPI Ref No 987654321012. Avl Bal INR 9000.00`); setPreview(null); }}>Use a fictional example</button><button className="button secondary" disabled={busy || !text.trim()} onClick={() => void parse()}>{busy ? "Checking…" : "Preview details"}</button></div>
      {preview && <div className="capture-preview"><b>{preview.ignored ? "This alert will be ignored" : `${preview.description ?? "Merchant needs review"} · ${money(preview.amount)}`}</b>{!preview.ignored && <p>{preview.date} · {preview.type ?? "Choose type"} · {preview.account ?? "No account ending"} · {preview.reference ? `Ref ${preview.reference}` : "No bank reference"}</p>}<ul>{preview.notes.map(note => <li key={note}>{note}</li>)}</ul>{preview.autoEligible && <p className="capture-good"><Check size={13}/>All required payment fields were found. Live collection also checks the sender and existing transactions.</p>}{!preview.ignored && <button className="button primary" disabled={busy} onClick={() => void addToReview()}>Add to review queue</button>}</div>}
    </div>}
    <div className="capture-alerts">{entries.length ? entries.map(alert => <article className="capture-alert" key={alert.id}><div className="capture-alert-main"><strong>{alert.description ?? "Bank alert"}</strong><span>{alert.sender} · {alert.date ?? "No payment date"}{alert.account ? ` · ${alert.account}` : ""}</span>{alert.reference && <small>Bank reference: {alert.reference}</small>}{alert.status === "REVIEW" && alert.notes[0] && <p>{alert.notes[0]}</p>}</div><div className="capture-alert-value"><b>{money(alert.amount)}</b><span className={`capture-badge ${alert.status.toLowerCase()}`}>{labels[alert.status] ?? alert.status}</span>{alert.status === "REVIEW" && <div className="capture-row-buttons"><button className="text-button" disabled={busy} onClick={() => { setSelected(alert); setError(""); }}>Review</button><button className="icon-button" disabled={busy} aria-label={`Dismiss ${alert.description ?? "alert"}`} onClick={() => void dismiss(alert)}><X size={14}/></button></div>}</div></article>) : <div className="capture-empty"><Check size={17}/><span>{filter === "REVIEW" ? "No alerts awaiting review." : "No bank alerts collected yet."}</span></div>}</div>
    <details className="capture-setup"><summary>How local iPhone capture works</summary><ol><li>Forward iPhone SMS messages to Messages on this Mac using the same Apple Account.</li><li>Approve the bank sender IDs WealthOS may collect. The collector opens Messages read-only and keeps a checkpoint for retries.</li><li>macOS may require Full Disk Access for the app running the collector. Enable it only after approving this access.</li><li>Keep this Mac, Docker, the backend, and the collector running. Alerts can catch up after messages sync and the Mac wakes.</li></ol><p>Setup is completed locally with your permission. A running collector checks every 30 seconds; the dashboard refreshes this panel every 15 seconds. Only INR is supported, and unsupported or uncertain alerts need review.</p></details>
    {selected && <div className="modal-backdrop"><form className="modal capture-review-modal" onSubmit={accept}><div className="modal-head"><div><div className="eyebrow">CHECK DETECTED DETAILS</div><h2>Review bank alert</h2></div><button type="button" className="icon-button" aria-label="Close bank alert review" onClick={() => { setSelected(null); setError(""); }}><X size={18}/></button></div>{error && <p className="capture-error" role="alert">{error}</p>}<ul className="capture-review-notes">{selected.notes.map(note => <li key={note}>{note}</li>)}</ul><label>Description<input name="description" defaultValue={selected.description ?? ""} required maxLength={200}/></label><div className="form-row"><label>Amount (₹)<input name="amount" type="number" min="0.01" step="0.01" defaultValue={selected.amount ?? ""} required/></label><label>Date<input name="date" type="date" defaultValue={selected.date ?? ""} required/></label></div><label>Type<select name="type" defaultValue={selected.type ?? "EXPENSE"}><option value="EXPENSE">Expense</option><option value="INCOME">Income</option></select></label><label>Category<select name="categoryId" defaultValue={categories.find(c => c.name === selected.suggestedCategory)?.id ?? ""}><option value="">Uncategorized</option>{categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label><div className="form-row"><label>Account ending<input name="account" maxLength={80} defaultValue={selected.account ?? ""}/></label><label>Bank reference<input name="reference" maxLength={80} defaultValue={selected.reference ?? ""}/></label></div><label className="capture-confirm"><input type="checkbox" name="separatePayment"/>I checked similar entries; this is a separate payment.</label><div className="modal-buttons"><button type="button" className="button secondary" onClick={() => setSelected(null)}>Cancel</button><button type="submit" className="button primary" disabled={busy}>{busy ? "Saving…" : "Record transaction"}</button></div></form></div>}
  </section>;
}
