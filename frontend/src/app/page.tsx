"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { ArrowDownLeft, ArrowUpRight, CalendarDays, ChevronDown, CircleHelp, FileUp, LayoutDashboard, LoaderCircle, Plus, Search, Settings2, WalletCards, X } from "lucide-react";
import CapturePanel from "./CapturePanel";

const API = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api";
type Tx = { id: string; date: string; description: string; amount: number; type: "INCOME" | "EXPENSE"; categoryId?: string; categoryName?: string; account?: string; reference?: string; source?: string };
type Category = { id: string; name: string; color: string; kind: string };
type Summary = { month: string; income: number; expenses: number; balance: number; byCategory: { name: string; color: string; total: number }[]; recent: Tx[] };
const money = (n: number) => new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 0, maximumFractionDigits: 2 }).format(n || 0);
const monthLabel = (month: string) => new Date(`${month}-02T12:00:00`).toLocaleDateString("en-IN", { month: "long", year: "numeric" });

export default function Home() {
  const current = new Date();
  const [month, setMonth] = useState(`${current.getFullYear()}-${String(current.getMonth() + 1).padStart(2, "0")}`);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [categories, setCategories] = useState<Category[]>([]);
  const [transactions, setTransactions] = useState<Tx[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [modal, setModal] = useState(false);
  const [search, setSearch] = useState("");
  const [toast, setToast] = useState("");
  const uploadRef = useRef<HTMLInputElement>(null);

  async function load() {
    setLoading(true); setError("");
    try {
      const [s, c, t] = await Promise.all([fetch(`${API}/dashboard?month=${month}`), fetch(`${API}/categories`), fetch(`${API}/transactions`)]);
      if (![s, c, t].every(r => r.ok)) throw new Error("The API could not load your data.");
      setSummary(await s.json()); setCategories(await c.json()); setTransactions(await t.json());
    } catch { setError("Can’t reach WealthOS yet. Start PostgreSQL and the API, then refresh."); }
    finally { setLoading(false); }
  }
  useEffect(() => { void load(); }, [month]);

  const visible = useMemo(() => transactions.filter(t => t.description.toLowerCase().includes(search.toLowerCase()) || (t.categoryName ?? "").toLowerCase().includes(search.toLowerCase())), [transactions, search]);
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault(); const form = new FormData(e.currentTarget);
    const payload = { date: form.get("date"), description: form.get("description"), amount: Number(form.get("amount")), type: form.get("type"), categoryId: form.get("categoryId") || null, account: form.get("account") || null };
    const response = await fetch(`${API}/transactions`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) });
    if (!response.ok) { const body = await response.json().catch(() => ({})); setToast(body.error ?? "Couldn’t save this transaction."); return; }
    setModal(false); setToast("Transaction added"); await load(); setTimeout(() => setToast(""), 2800);
  }
  async function importCsv(file?: File) {
    if (!file) return; const form = new FormData(); form.append("file", file);
    try { const response = await fetch(`${API}/imports/csv`, { method: "POST", body: form }); const result = await response.json(); if (!response.ok) throw new Error(result.error); setToast(`Imported ${result.imported}; skipped ${result.duplicates} duplicate${result.duplicates === 1 ? "" : "s"}.`); await load(); }
    catch (e) { setToast(e instanceof Error ? e.message : "Couldn’t import this CSV."); }
    setTimeout(() => setToast(""), 3800);
  }

  return <main className="shell">
    <aside className="sidebar">
      <a className="brand" href="#"><span className="brand-mark">w</span><span>wealth<span className="brand-light">os</span></span></a>
      <div className="side-label">WORKSPACE</div><button className="nav active"><LayoutDashboard size={17}/>Overview</button><button className="nav" onClick={() => document.getElementById("transactions")?.scrollIntoView({ behavior: "smooth" })}><WalletCards size={17}/>Transactions</button>
      <div className="side-bottom"><div className="side-card"><span className="side-card-icon"><CircleHelp size={17}/></span><strong>Your money, made clear.</strong><small>A simple space to understand your spending.</small></div><button className="nav"><Settings2 size={17}/>Settings</button><div className="profile"><div className="avatar">S</div><div><strong>My workspace</strong><small>Personal account</small></div><ChevronDown size={15}/></div></div>
    </aside>
    <section className="main-area">
      <header className="topbar"><div className="crumb">Workspace <span>/</span> <b>Overview</b></div><div className="top-actions"><button className="icon-button" aria-label="Search" onClick={() => document.getElementById("search-box")?.focus()}><Search size={18}/></button><span className="avatar small">S</span></div></header>
      <div className="content">
        <div className="page-heading"><div><div className="eyebrow">YOUR MONEY AT A GLANCE</div><h1>Good afternoon<span className="period">.</span></h1><p>A clear picture of where your money is going.</p></div><div className="heading-actions"><label className="month-select"><CalendarDays size={16}/><input aria-label="Choose month" type="month" value={month} onChange={e => setMonth(e.target.value)}/></label><button className="button primary" onClick={() => setModal(true)}><Plus size={17}/>Add transaction</button></div></div>
        {error && <div className="notice">{error}</div>}
        <div className="section-overline">MONTHLY SUMMARY <span>{monthLabel(month)}</span></div>
        <div className="stats-grid">
          <Stat label="Total income" value={money(summary?.income ?? 0)} icon={<ArrowDownLeft size={17}/>} color="green" note="Money in this month" loading={loading}/>
          <Stat label="Total expenses" value={money(summary?.expenses ?? 0)} icon={<ArrowUpRight size={17}/>} color="orange" note="Money out this month" loading={loading}/>
          <Stat label="Net balance" value={money(summary?.balance ?? 0)} icon={<WalletCards size={17}/>} color="blue" note="Income minus expenses" loading={loading}/>
        </div>
        <CapturePanel api={API} categories={categories} onRecorded={() => void load()}/>
        <div className="dashboard-grid">
          <section className="panel category-panel"><div className="panel-head"><div><h2>Spending by category</h2><p>See what’s taking the biggest share.</p></div><button className="text-button">This month <ChevronDown size={14}/></button></div>
            {loading ? <div className="loading"><LoaderCircle className="spin" size={20}/></div> : summary?.byCategory.length ? <div className="category-list">{summary.byCategory.map((item, i) => { const pct = summary.expenses ? Math.round(item.total / summary.expenses * 100) : 0; return <div className="category-row" key={item.name}><div className="category-line"><span className="category-dot" style={{ background: item.color }}/><span className="category-name">{item.name}</span><span className="category-amount">{money(item.total)}</span><span className="category-pct">{pct}%</span></div><div className="bar-track"><div className="bar-fill" style={{ width: `${pct}%`, background: item.color, animationDelay: `${i * 80}ms` }}/></div></div>; })}</div> : <Empty title="No spending to show" text="Add an expense or import a CSV to see your category breakdown."/>}
          </section>
          <section className="panel insight-panel"><div className="panel-head"><div><h2>Monthly snapshot</h2><p>Your income and spending, side by side.</p></div></div><div className="snapshot"><div className="snapshot-number">{summary?.income ? Math.round(summary.expenses / summary.income * 100) : 0}<span>%</span></div><div className="snapshot-caption">of this month’s income<br/>has been spent</div></div><div className="snapshot-track"><div style={{ width: `${Math.min(summary?.income ? summary.expenses / summary.income * 100 : 0, 100)}%` }}/></div><div className="snapshot-foot"><span><i className="dot green-dot"/>Income</span><b>{money(summary?.income ?? 0)}</b></div><div className="snapshot-foot"><span><i className="dot orange-dot"/>Expenses</span><b>{money(summary?.expenses ?? 0)}</b></div></section>
        </div>
        <section className="panel transactions-panel" id="transactions"><div className="panel-head transaction-head"><div><h2>Recent transactions</h2><p>Your latest money activity.</p></div><div className="table-actions"><label className="search-field"><Search size={15}/><input id="search-box" placeholder="Search transactions" value={search} onChange={e => setSearch(e.target.value)}/></label><button className="button secondary" onClick={() => uploadRef.current?.click()}><FileUp size={16}/>Import CSV</button><input ref={uploadRef} type="file" accept=".csv,text/csv" hidden onChange={e => { void importCsv(e.target.files?.[0]); e.currentTarget.value=""; }}/></div></div>
          <div className="table-wrap"><table><thead><tr><th>TRANSACTION</th><th>CATEGORY</th><th>DATE</th><th>ACCOUNT</th><th className="align-right">AMOUNT</th></tr></thead><tbody>{visible.slice(0, 12).map(t => <tr key={t.id}><td><span className={`transaction-icon ${t.type === "INCOME" ? "income-icon" : "expense-icon"}`}>{t.type === "INCOME" ? <ArrowDownLeft size={16}/> : <ArrowUpRight size={16}/>}</span><span className="transaction-desc"><b>{t.description}</b><small>{t.type === "INCOME" ? "Income" : "Expense"}</small></span></td><td><span className="category-pill">{t.categoryName ?? "Uncategorized"}</span></td><td className="date-cell">{new Date(`${t.date}T12:00:00`).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })}</td><td className="date-cell">{t.account ?? "—"}</td><td className={`align-right amount ${t.type === "INCOME" ? "positive" : ""}`}>{t.type === "INCOME" ? "+" : "−"}{money(t.amount)}</td></tr>)}</tbody></table>{!loading && visible.length === 0 && <Empty title="Your transaction list is empty" text="Add a transaction or import a CSV to get started."/>}{loading && <div className="loading"><LoaderCircle className="spin" size={20}/></div>}</div><div className="table-foot">Showing {Math.min(visible.length, 12)} of {visible.length} transactions <button onClick={() => document.getElementById("transactions")?.scrollIntoView({ behavior: "smooth" })}>View all <span>→</span></button></div>
        </section>
        <footer>Made for a clearer relationship with money <span>·</span> Your data stays on your device</footer>
      </div>
    </section>
    {modal && <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) setModal(false); }}><form className="modal" onSubmit={submit}><div className="modal-head"><div><div className="eyebrow">NEW ENTRY</div><h2>Add transaction</h2></div><button type="button" className="icon-button" onClick={() => setModal(false)}><X size={18}/></button></div><label>Type<select name="type"><option value="EXPENSE">Expense</option><option value="INCOME">Income</option></select></label><label>Description<input name="description" placeholder="e.g. Grocery shopping" required maxLength={200}/></label><div className="form-row"><label>Amount (₹)<input name="amount" type="number" min="0.01" step="0.01" placeholder="0.00" required/></label><label>Date<input name="date" type="date" defaultValue={new Date().toISOString().slice(0,10)} required/></label></div><label>Category<select name="categoryId"><option value="">Uncategorized</option>{categories.map(c=><option value={c.id} key={c.id}>{c.name}</option>)}</select></label><label>Account <span className="optional">Optional</span><input name="account" placeholder="e.g. HDFC debit card" maxLength={80}/></label><div className="modal-buttons"><button className="button secondary" type="button" onClick={() => setModal(false)}>Cancel</button><button className="button primary" type="submit"><Plus size={16}/>Save transaction</button></div></form></div>}
    {toast && <div className="toast">{toast}</div>}
  </main>;
}

function Stat({ label, value, icon, color, note, loading }: { label: string; value: string; icon: React.ReactNode; color: string; note: string; loading: boolean }) {
  return <article className="stat-card"><div className="stat-top"><span>{label}</span><span className={`stat-icon ${color}`}>{icon}</span></div><div className="stat-value">{loading ? <span className="skeleton"/> : value}</div><div className="stat-note">{note}</div></article>;
}
function Empty({ title, text }: { title: string; text: string }) { return <div className="empty"><span className="empty-symbol"><WalletCards size={20}/></span><b>{title}</b><p>{text}</p></div>; }
