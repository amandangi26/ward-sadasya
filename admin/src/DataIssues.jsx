import React, { useEffect, useMemo, useState } from 'react';
import * as XLSX from 'xlsx';
import { supabase, fetchAll, fmt } from './lib.js';
import { Check, X, TriangleAlert, Download } from './icons.jsx';

// The structure: Lok Sabha > Congress organisational district > Vidhan Sabha > MCD ward.
// Leaders: DPCC President > General Secretary > District Presidents > Assembly Observers > ward members.
const KIND = { member: 'Ward member', observer: 'Assembly Observer', dp: 'District President', gs: 'General Secretary', president: 'DPCC President' };
const phone = (v) => { const d = String(v ?? '').split('.')[0].replace(/\D/g, '').slice(-10); return /^[6-9]\d{9}$/.test(d) ? d : ''; };
const txt = (v) => String(v ?? '').trim();
const sheet = (wb, name) => {
  const key = wb.SheetNames.find((n) => n.trim().toLowerCase().startsWith(name));
  return key ? XLSX.utils.sheet_to_json(wb.Sheets[key], { defval: '' }) : null;
};
async function inChunks(table, rows, opts) {
  for (let i = 0; i < rows.length; i += 500) {
    const { error } = await supabase.from(table).upsert(rows.slice(i, i + 500), opts);
    if (error) throw new Error(`${table}: ${error.message}`);
  }
}

/** Reads the master Excel and splits it into clean rows and rows that need a person to look at them. */
function readMaster(wb) {
  const W = sheet(wb, 'wards'), M = sheet(wb, 'master'), O = sheet(wb, 'observers');
  if (!W || !M || !O) throw new Error('The file needs three sheets: "Wards 250", "Master List" and "Observers".');
  const issues = [];
  const wards = W.filter((r) => Number(r['Ward No']) >= 1).map((r) => ({
    ward_no: Number(r['Ward No']), ward_name: txt(r['Ward Name']) || null, lok_sabha: txt(r['Lok Sabha']) || null,
    district: txt(r['Congress District']) || null, ac_no: Number(r['AC No']) || null, assembly: txt(r['Assembly Constituency']) || null,
    revenue_district: txt(r['Revenue District']) || null,
  }));
  const members = [], seen = new Map();
  for (const r of M) {
    const base = {
      name: txt(r['Aspirant Name']), mobile: phone(r['Phone']), raw_phone: txt(r['Phone']), ward_no: Number(r['Ward No']) || null,
      ward_name: txt(r['Ward Name']) || null, assembly: txt(r['Assembly Constituency']) || null, district: txt(r['Congress District']) || null,
      lok_sabha: txt(r['Lok Sabha']) || null, age: Number(r['Age']) || null, alt_mobile: phone(r['Alt Phone']) || null, sheet_row: r['S.No'] || null,
    };
    const why = [];
    if (!base.name) why.push('Name missing');
    if (!base.ward_no || base.ward_no > 250) why.push('Not an MCD ward (NDMC / Cantonment) or ward number missing');
    if (!base.mobile) why.push(base.raw_phone ? `Phone incomplete or wrong (${base.raw_phone})` : 'Phone missing');
    else if (seen.has(base.mobile)) { const o = seen.get(base.mobile); why.push(`Same phone as ${o.name} (ward ${o.ward_no}). One number can log in to one ward only.`); }
    if (why.length) { issues.push({ kind: 'member', issue: why.join('; '), data: base }); continue; }
    seen.set(base.mobile, base);
    const { raw_phone, sheet_row, ...row } = base;
    members.push({ ...row, active: true });
  }
  const observers = [], seenObs = new Map();
  for (const r of O) {
    const base = { name: txt(r['Observer']), mobile: phone(r['Observer Phone']), raw_phone: txt(r['Observer Phone']), assembly: txt(r['Assembly Constituency']) || null, district: txt(r['Congress District']) || null, lok_sabha: txt(r['Lok Sabha']) || null };
    const why = [];
    if (!base.name) why.push('Observer name missing');
    if (!base.mobile) why.push(base.raw_phone ? `Phone incomplete or wrong (${base.raw_phone})` : 'Phone missing');
    else if (seenObs.has(base.mobile)) why.push(`Same phone as the observer of ${seenObs.get(base.mobile).assembly}. One number can observe one assembly only.`);
    if (why.length) { issues.push({ kind: 'observer', issue: why.join('; '), data: base }); continue; }
    seenObs.set(base.mobile, base);
    observers.push({ mobile: base.mobile, name: base.name, role: 'observer', district: base.district, assembly: base.assembly, active: true });
  }
  const districts = [...new Set(wards.map((w) => w.district).filter(Boolean))].sort();
  const both = observers.filter((o) => seen.has(o.mobile)).length;
  return { wards, members, observers, issues, districts, both };
}

function IssueRow({ it, wards, onDone }) {
  const [d, setD] = useState(it.data || {});
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const set = (k) => (e) => setD({ ...d, [k]: e.target.value });
  const assemblies = useMemo(() => [...new Set(wards.map((w) => w.assembly).filter(Boolean))].sort(), [wards]);
  const districts = useMemo(() => [...new Set(wards.map((w) => w.district).filter(Boolean))].sort(), [wards]);

  const close = async (status) => { await supabase.from('data_issues').update({ status, resolved_at: new Date().toISOString(), data: d }).eq('id', it.id); onDone(); };
  const push = async () => {
    setErr(''); setBusy(true);
    try {
      const mobile = phone(d.mobile); const name = txt(d.name);
      if (!name) throw new Error('Add the name.');
      if (!mobile) throw new Error('Add a correct 10-digit mobile number.');
      if (it.kind === 'member') {
        const w = wards.find((x) => x.ward_no === Number(d.ward_no));
        if (!w) throw new Error('Pick the ward.');
        const { data: ex } = await supabase.from('members').select('name, ward_no').eq('mobile', mobile).maybeSingle();
        if (ex && (ex.name.trim().toLowerCase() !== name.toLowerCase() || ex.ward_no !== w.ward_no)) throw new Error(`This number is already ${ex.name}, ward ${ex.ward_no}. Use a different number, or dismiss this row.`);
        const { error } = await supabase.from('members').upsert({ mobile, name, ward_no: w.ward_no, ward_name: w.ward_name, assembly: w.assembly, district: w.district, lok_sabha: w.lok_sabha, age: Number(d.age) || null, alt_mobile: phone(d.alt_mobile) || null, active: true });
        if (error) throw error;
      } else {
        const role = it.kind;
        const row = { mobile, name, role, active: true, district: null, assembly: null };
        if (role === 'observer') {
          const w = wards.find((x) => x.assembly === d.assembly);
          if (!w) throw new Error('Pick the Vidhan Sabha.');
          row.assembly = w.assembly; row.district = w.district;
        }
        if (role === 'dp') { if (!d.district) throw new Error('Pick the district.'); row.district = d.district; }
        const { data: ex } = await supabase.from('leaders').select('name, role').eq('mobile', mobile).maybeSingle();
        if (ex && (ex.role !== role || ex.name.trim().toLowerCase() !== name.toLowerCase())) throw new Error(`This number is already ${ex.name} (${KIND[ex.role]}). One number can hold one leader post.`);
        const { error } = await supabase.from('leaders').upsert(row);
        if (error) throw error;
      }
      await close('resolved');
    } catch (e) { setErr(e.message || String(e)); }
    setBusy(false);
  };

  return (
    <tr>
      <td><span className="tag mute">{KIND[it.kind]}</span><div style={{ color: '#9A4A0B', fontSize: 12.5, marginTop: 6, maxWidth: 260 }}>{it.issue}</div>{d.sheet_row && <div className="muted" style={{ fontSize: 11.5 }}>Sheet row {d.sheet_row}</div>}</td>
      <td><input type="text" value={d.name || ''} onChange={set('name')} placeholder="Name" style={{ width: 170 }} /></td>
      <td><input type="tel" value={d.mobile || ''} onChange={set('mobile')} placeholder="10-digit mobile" style={{ width: 140 }} /></td>
      <td>
        {it.kind === 'member' && <select value={d.ward_no || ''} onChange={set('ward_no')} style={{ maxWidth: 220 }}><option value="">Pick ward</option>{wards.map((w) => <option key={w.ward_no} value={w.ward_no}>{w.ward_no} · {w.ward_name} ({w.assembly})</option>)}</select>}
        {it.kind === 'observer' && <select value={d.assembly || ''} onChange={set('assembly')} style={{ maxWidth: 220 }}><option value="">Pick Vidhan Sabha</option>{assemblies.map((a) => <option key={a}>{a}</option>)}</select>}
        {it.kind === 'dp' && <select value={d.district || ''} onChange={set('district')} style={{ maxWidth: 220 }}><option value="">Pick district</option>{districts.map((a) => <option key={a}>{a}</option>)}</select>}
        {(it.kind === 'gs' || it.kind === 'president') && <span className="muted">All Delhi</span>}
        {err && <div className="notice" style={{ marginTop: 6, maxWidth: 260 }}>{err}</div>}
      </td>
      <td style={{ whiteSpace: 'nowrap' }}>
        <button className="btn sm" disabled={busy} onClick={push}><Check size={14} />Save and add</button>{' '}
        <button className="btn ghost sm" disabled={busy} onClick={() => close('dismissed')}><X size={14} />Dismiss</button>
      </td>
    </tr>
  );
}

export default function DataIssues() {
  const [issues, setIssues] = useState([]);
  const [wards, setWards] = useState([]);
  const [counts, setCounts] = useState({});
  const [kind, setKind] = useState('all');
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState(false);
  const [shown, setShown] = useState(50);

  const load = async () => {
    setIssues(await fetchAll('data_issues', '*', (q) => q.eq('status', 'open').order('kind').order('id')));
    setWards(await fetchAll('wards', '*', (q) => q.order('ward_no')));
    const c = async (t, f) => { let q = supabase.from(t).select('*', { count: 'exact', head: true }); if (f) q = f(q); return (await q).count ?? 0; };
    setCounts({ wards: await c('wards'), members: await c('members', (q) => q.eq('active', true)), observers: await c('leaders', (q) => q.eq('role', 'observer').eq('active', true)), dps: await c('leaders', (q) => q.eq('role', 'dp').eq('active', true)) });
  };
  useEffect(() => { load(); }, []);

  const importFile = async (e) => {
    const file = e.target.files?.[0]; if (!file) return;
    setBusy(true); setMsg('Reading the file…');
    try {
      const m = readMaster(XLSX.read(await file.arrayBuffer()));
      setMsg('Loading wards, members and observers…');
      await inChunks('wards', m.wards.map((w) => ({ ...w, updated_at: new Date().toISOString() })));
      await inChunks('members', m.members, { onConflict: 'mobile' });
      await inChunks('leaders', m.observers, { onConflict: 'mobile' });
      // Posts that still have nobody
      const leaders = await fetchAll('leaders', 'role, district', (q) => q.eq('active', true));
      const gaps = [];
      if (!leaders.some((l) => l.role === 'president')) gaps.push({ kind: 'president', issue: 'DPCC President login not added yet', data: { name: 'Devender Yadav' } });
      if (!leaders.some((l) => l.role === 'gs')) gaps.push({ kind: 'gs', issue: 'General Secretary not added yet', data: {} });
      for (const d of m.districts) if (!leaders.some((l) => l.role === 'dp' && (l.district || '').toLowerCase() === d.toLowerCase())) gaps.push({ kind: 'dp', issue: `No District President added for ${d}`, data: { district: d } });
      // A fresh import replaces the open list, so the same rows are not listed twice.
      await supabase.from('data_issues').delete().eq('status', 'open');
      const all = [...m.issues, ...gaps];
      for (let i = 0; i < all.length; i += 500) { const { error } = await supabase.from('data_issues').insert(all.slice(i, i + 500)); if (error) throw error; }
      setMsg(`Loaded ${fmt(m.wards.length)} wards, ${fmt(m.members.length)} ward members and ${fmt(m.observers.length)} observers. ${fmt(m.both)} observers are also ward members (they will see both profiles). ${fmt(all.length)} rows need a look and are listed here.`);
      await load();
    } catch (err) { setMsg('Import stopped: ' + (err.message || err)); }
    setBusy(false); e.target.value = '';
  };

  const exportIssues = () => {
    const ws = XLSX.utils.json_to_sheet(issues.map((i) => ({ type: KIND[i.kind], problem: i.issue, ...i.data })));
    const wb = XLSX.utils.book_new(); XLSX.utils.book_append_sheet(wb, ws, 'Data discrepancies'); XLSX.writeFile(wb, 'data-discrepancies.xlsx');
  };

  const list = issues.filter((i) => kind === 'all' || i.kind === kind);
  const n = (k) => issues.filter((i) => i.kind === k).length;
  return (
    <>
      <section className="kpis">
        <div className="card kpi"><div className="l">Wards in the master</div><div className="v">{fmt(counts.wards || 0)}</div><div className="s">of 250</div></div>
        <div className="card kpi"><div className="l">Ward members loaded</div><div className="v">{fmt(counts.members || 0)}</div></div>
        <div className="card kpi"><div className="l">Observers loaded</div><div className="v">{fmt(counts.observers || 0)}</div><div className="s">{fmt(counts.dps || 0)} District Presidents</div></div>
        <div className="card kpi"><div className="l">Rows to fix</div><div className="v" style={{ color: issues.length ? '#C2560A' : undefined }}>{fmt(issues.length)}</div></div>
      </section>
      <section className="card">
        <h2>Import the master sheet</h2>
        <p className="sub">Upload the master Excel in the same format every time: sheets "Wards 250", "Master List" and "Observers". The structure is Lok Sabha, then Congress organisational district, then Vidhan Sabha, then MCD ward. Clean rows load straight away and existing numbers are updated. Anything unclear is listed below instead of being guessed.</p>
        {msg && <div className="notice ok" style={{ marginTop: 10 }}>{msg}</div>}
        <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
          <label className="btn" style={{ display: 'inline-flex', alignItems: 'center' }}>{busy ? 'Importing…' : 'Upload master Excel'}<input type="file" accept=".xlsx,.xls" onChange={importFile} disabled={busy} hidden /></label>
          {issues.length > 0 && <button className="btn ghost" onClick={exportIssues}><Download size={14} /> Download this list</button>}
        </div>
      </section>
      <section className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
          <div>
            <h2 style={{ display: 'flex', alignItems: 'center', gap: 8 }}><TriangleAlert size={18} color="#C2560A" />Data discrepancies ({list.length})</h2>
            <p className="sub">Correct the row and press "Save and add". It goes into the ward member list or the leader list and leaves this page. "Dismiss" removes a row you don't want loaded.</p>
          </div>
          <div className="seg">
            {[['all', `All ${issues.length}`], ['member', `Ward members ${n('member')}`], ['observer', `Observers ${n('observer')}`], ['dp', `District Presidents ${n('dp')}`], ['gs', `GS ${n('gs')}`], ['president', `President ${n('president')}`]].map(([k, l]) => (
              <button key={k} className={kind === k ? 'on' : ''} onClick={() => { setKind(k); setShown(50); }}>{l}</button>))}
          </div>
        </div>
        <div className="tbl"><table>
          <thead><tr><th>Problem</th><th>Name</th><th>Mobile</th><th>Area</th><th /></tr></thead>
          <tbody>{list.slice(0, shown).map((it) => <IssueRow key={it.id} it={it} wards={wards} onDone={load} />)}</tbody>
        </table></div>
        {list.length > shown && <button className="btn ghost" style={{ marginTop: 10 }} onClick={() => setShown(shown + 50)}>Show {Math.min(50, list.length - shown)} more</button>}
        {!list.length && <p className="muted">Nothing to fix here.</p>}
      </section>
    </>
  );
}
