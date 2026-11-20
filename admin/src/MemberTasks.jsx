import React, { useEffect, useState } from 'react';
import { supabase, fetchAll, timeAgo, wardLabel, istDate, istToday, dayLabel, districtOf } from './lib.js';
import { Check, X } from './icons.jsx';

// Tasks members create for work they did on their own (2 photos and 2 videos). 100 points on approval.
export default function MemberTasks() {
  const [rows, setRows] = useState([]);
  const [members, setMembers] = useState({});
  const [files, setFiles] = useState({});
  const [status, setStatus] = useState('pending');
  const [msg, setMsg] = useState('');
  const [date, setDate] = useState('');   // '' = every date

  const load = async () => {
    const list = await fetchAll('member_tasks', '*', (q) => q.order('created_at', { ascending: false }));
    setRows(list);
    setMembers(Object.fromEntries((await fetchAll('members', 'mobile, name, ward_no, district, zone')).map((m) => [m.mobile, m])));
    const paths = [...new Set(list.filter((r) => (r.status === status || status === 'all') && (!date || istDate(r.created_at) === date)).slice(0, 60).flatMap((r) => (r.media || []).map((m) => m.path)))];
    if (paths.length) {
      const { data } = await supabase.storage.from('uploads').createSignedUrls(paths, 3600);
      setFiles(Object.fromEntries((data || []).filter((d) => d.signedUrl).map((d) => [d.path, d.signedUrl])));
    }
  };
  useEffect(() => { load(); }, [status, date]);

  const review = async (r, ok) => {
    const { data: u } = await supabase.auth.getUser();
    const { error } = await supabase.from('member_tasks').update({ status: ok ? 'approved' : 'rejected', reviewed_at: new Date().toISOString(), reviewed_by: u?.user?.email || 'admin' }).eq('id', r.id);
    setMsg(error ? error.message : ok ? 'Approved. 100 points added to the member.' : 'Rejected.');
    load();
  };
  const list = rows.filter((r) => (status === 'all' || r.status === status) && (!date || istDate(r.created_at) === date));
  const count = (s) => rows.filter((r) => r.status === s).length;

  return (
    <section className="card">
      <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', gap: 12, alignItems: 'center' }}>
        <div>
          <h2>Member tasks</h2>
          <p className="sub">Work members did on their own, with 2 photos and 2 videos. Check that it really happened. Approving adds 100 points.</p>
        </div>
        <div className="seg">
          {[['pending', `Waiting ${count('pending')}`], ['approved', `Approved ${count('approved')}`], ['rejected', `Rejected ${count('rejected')}`], ['all', 'All']].map(([k, l]) => (
            <button key={k} className={status === k ? 'on' : ''} onClick={() => setStatus(k)}>{l}</button>))}
        </div>
      </div>
      <div className="filters" style={{ marginTop: 12 }}>
        <label htmlFor="mtdate">Date sent</label>
        <input id="mtdate" type="date" value={date} max={istToday()} onChange={(e) => setDate(e.target.value)} />
        <button className={'btn sm ' + (date ? 'ghost' : '')} onClick={() => setDate('')}>All dates</button>
        <span className="muted" style={{ fontSize: 13 }}>{list.length} shown{date ? ` · sent on ${dayLabel(date)}` : ''}</span>
      </div>
      {msg && <div className="notice ok" style={{ marginTop: 10 }}>{msg}</div>}
      <div className="mt-grid">
      {list.map((r) => { const m = members[r.mobile] || {}; return (
        <article key={r.id} className="mt-card">
          <header>
            <div>
              <b>{m.name || r.mobile}</b>
              <div className="muted" style={{ fontSize: 12.5 }}>{r.mobile}{m.ward_no ? ' · ' + wardLabel(m.ward_no) : ''}{districtOf(m) ? ' · ' + districtOf(m) : ''}</div>
            </div>
            <span className={'tag ' + (r.status === 'approved' ? 'ok' : r.status === 'pending' ? 'live' : 'mute')}>{r.status === 'approved' ? 'Approved' : r.status === 'pending' ? 'Waiting' : 'Rejected'}</span>
          </header>
          <h3>{r.title}</h3>
          {r.description && <p>{r.description}</p>}
          <div className="mt-media">
            {(r.media || []).map((x) => files[x.path] ? (x.kind === 'video'
              ? <video key={x.path} src={files[x.path]} controls />
              : <a key={x.path} href={files[x.path]} target="_blank" rel="noreferrer"><img src={files[x.path]} alt="" /></a>) : (
              <div key={x.path} style={{ padding: '8px 12px', background: '#F3F4F6', borderRadius: '8px', fontSize: '12px', color: '#4B5563', display: 'flex', alignItems: 'center', gap: '6px' }}>
                <span>📦</span>
                <span>{x.kind === 'video' ? 'वीडियो' : 'फोटो'} बैकअप में आर्काइव्ड है</span>
              </div>
            ))}
          </div>
          <footer>
            <span className="muted" style={{ fontSize: 12.5 }}>Sent {new Date(r.created_at).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })} · {timeAgo(r.created_at)}</span>
            {r.status === 'pending' && <span style={{ whiteSpace: 'nowrap' }}><button className="btn sm" onClick={() => review(r, true)}><Check size={14} />Approve</button>{' '}<button className="btn warn sm" onClick={() => review(r, false)}><X size={14} />Reject</button></span>}
          </footer>
        </article>); })}
      </div>
      {!list.length && <p className="muted">Nothing here.</p>}
    </section>
  );
}
