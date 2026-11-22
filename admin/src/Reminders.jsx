import React, { useEffect, useMemo, useState } from 'react';
import { supabase, wardLabel, callFn, fetchAll, districtOf, istDate, istToday, dayLabel, fmt } from './lib.js';

// Ready-made messages. Picking one fills the title and the message; both can still be edited.
const TEMPLATES = [
  ['आज के टास्क याद दिलाएं', 'आज के टास्क बाकी हैं', 'आज के टास्क रात 11:59 तक पूरे करें। 2 फोटो और 2 वीडियो अपलोड करना न भूलें।'],
  ['पोस्ट शेयर करें', 'नई पोस्ट शेयर करें', 'देवेंद्र यादव जी की नई पोस्ट आ गई है। X, Facebook और Instagram पर अभी शेयर करें।'],
  ['अध्यक्ष जी का नया संदेश', 'अध्यक्ष जी का संदेश', 'प्रदेश कांग्रेस अध्यक्ष देवेंद्र यादव जी का नया संदेश ऐप में आ गया है। अभी देखें।'],
  ['बैठक की सूचना', 'बैठक की सूचना', 'कल सुबह 11 बजे बैठक है। कृपया समय पर पहुँचें।'],
  ['ऐप अपडेट करें', 'ऐप का नया वर्शन', 'ऐप का नया वर्शन आ गया है। कृपया आज ही अपडेट कर लें।'],
  ['प्रोफ़ाइल पूरी करें', 'अपनी प्रोफ़ाइल पूरी करें', 'ऐप में अपनी फोटो और पूरी जानकारी भरकर प्रोफ़ाइल अपडेट करें।'],
  ['अच्छे काम की बधाई', 'बहुत बढ़िया काम', 'आप सबने इस हफ़्ते बहुत अच्छा काम किया है। ऐसे ही जुटे रहें।'],
];

// Who a notification can go to. Ward-side people get it in the app's notification screen; leaders get it in their inbox.
const LEADER_LEVELS = [['gs', 'General Secretary'], ['dc', 'District Coordinators'], ['dp', 'District Presidents'], ['observer', 'Assembly Observers']];
const AUDIENCE_LABEL = { block: 'Block Presidents', gs: 'General Secretary', dc: 'District Coordinators', dp: 'District Presidents', observer: 'Assembly Observers' };

export default function Reminders() {
  const [ann, setAnn] = useState([]);
  const [body, setBody] = useState('');
  const [group, setGroup] = useState('members');   // members | block | leaders
  const [target, setTarget] = useState('all');     // members: all | pending | ward | zone
  const [level, setLevel] = useState('dp');        // leaders: gs | dc | dp | observer
  const [ward, setWard] = useState('1');
  const [zone, setZone] = useState('');
  const [title, setTitle] = useState('');
  const [template, setTemplate] = useState('');
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState(null);
  const [districts, setDistricts] = useState([]);
  const [date, setDate] = useState('');            // history: '' = all dates

  const load = async () => {
    const { data: a } = await supabase.from('announcements').select('*').order('created_at', { ascending: false }).limit(500);
    setAnn(a || []);
  };
  useEffect(() => {
    load();
    fetchAll('members', 'district, zone').then((rows) => setDistricts([...new Set(rows.map(districtOf).filter(Boolean))].sort()));
  }, []);

  const pickTemplate = (i) => {
    setTemplate(i);
    if (i === '') return;
    const t = TEMPLATES[Number(i)];
    setTitle(t[1]); setBody(t[2]);
  };

  const send = async (e) => {
    e.preventDefault();
    setSending(true); setResult(null);
    const heading = title.trim() || 'INC Delhi';
    if (group === 'leaders') {
      // Leaders: straight into each one's inbox in the app (red dot until they open it).
      const ls = await fetchAll('leaders', 'mobile, district', (q) => q.eq('active', true).eq('role', level));
      const to = ls.filter((l) => !zone || (l.district || '').trim().toLowerCase() === zone.trim().toLowerCase()).map((l) => l.mobile);
      let failed = '';
      for (let i = 0; i < to.length; i += 500) {
        const { error } = await supabase.from('inbox').insert(to.slice(i, i + 500).map((m) => ({
          to_mobile: m, from_name: 'INC Delhi', from_role: 'admin', kind: 'notice', text: title.trim() ? `${title.trim()}\n${body}` : body,
        })));
        if (error) { failed = error.message; break; }
      }
      if (!failed) await supabase.from('announcements').insert({ title: title.trim() || null, body, audience: level, district: zone || null, zone: zone || null, sent_count: to.length });
      setResult(failed ? { ok: false, text: failed } : { ok: true, text: `Sent to ${fmt(to.length)} ${AUDIENCE_LABEL[level]}. It is in their inbox in the app.` });
    } else if (group === 'block') {
      const ms = await fetchAll('members', 'mobile, district, zone', (q) => q.eq('active', true).not('block_no', 'is', null));
      const to = ms.filter((m) => !zone || districtOf(m).toLowerCase() === zone.trim().toLowerCase()).map((m) => m.mobile);
      const { error } = await supabase.from('announcements').insert({ title: title.trim() || null, body, audience: 'block', district: zone || null, zone: zone || null, sent_count: to.length });
      if (!error && to.length) await callFn('push', { title: heading, body, target: 'mobiles', mobiles: to, type: 'announcement' });
      setResult(error ? { ok: false, text: error.message } : { ok: true, text: `Sent to ${fmt(to.length)} Block Presidents. It shows in their app's notification screen.` });
    } else {
      // The push function saves the announcement and sends an instant high-priority notification.
      const { data, error } = await callFn('push', {
        title: heading, body,
        target: target === 'zone' ? 'district' : target,
        ward_no: Number(ward), district: zone, type: 'announcement', save_announcement: true,
      });
      if (error) {
        // Fallback: save the announcement directly so phones still get it at their next check-in.
        const row = { title: title.trim() || null, body, only_pending: target === 'pending', ward_no: target === 'ward' ? Number(ward) : null, district: target === 'zone' ? zone : null, zone: target === 'zone' ? zone : null };
        const { error: e2 } = await supabase.from('announcements').insert(row);
        setResult(e2 ? { ok: false, text: e2.message } : { ok: false, text: `Saved, but the instant push failed (${error}). Phones will get it at their next check-in.` });
      } else if (!data.pushed) {
        setResult({ ok: false, text: 'Saved. Firebase is not set up yet, so phones will get it at their next check-in (up to 15 min), not instantly.' });
      } else {
        setResult({ ok: true, text: target === 'pending' ? `Sent instantly to ${data.sent} phones.` : 'Sent instantly.' });
      }
    }
    setSending(false); setBody(''); setTitle(''); setTemplate(''); load();
  };

  const toLabel = (a) => {
    const where = a.ward_no ? wardLabel(a.ward_no) : (a.district || a.zone) || '';
    if (a.audience && AUDIENCE_LABEL[a.audience]) return AUDIENCE_LABEL[a.audience] + (where ? ' · ' + where : '');
    return where || (a.only_pending ? 'Members with pending tasks' : 'All ward members');
  };
  const byDay = useMemo(() => {
    const g = {};
    for (const a of ann) (g[istDate(a.created_at)] ||= []).push(a);
    return Object.entries(g).sort((a, b) => b[0].localeCompare(a[0]));
  }, [ann]);
  const days = byDay.filter(([d]) => !date || d === date);
  const needsDistrict = group === 'members' && target === 'zone';

  return (
    <>
      <section className="card">
        <h2>Send a notification</h2>
        <p className="sub">Ward members and Block Presidents see it in the app's notification screen (and instantly on the phone once Firebase is set up). Leaders get it in their inbox in the app, with a red dot until they open it.</p>
        <form onSubmit={send} className="form">
          <label>Who is it for
            <select value={group} onChange={(e) => { setGroup(e.target.value); setZone(''); }}>
              <option value="members">Ward members</option>
              <option value="block">Block Presidents</option>
              <option value="leaders">Leaders (one level)</option>
            </select>
          </label>
          {group === 'members' && (
            <label>Send to
              <select value={target} onChange={(e) => setTarget(e.target.value)}>
                <option value="all">Everyone</option>
                <option value="pending">Only members with pending tasks today</option>
                <option value="ward">One ward</option>
                <option value="zone">One district</option>
              </select>
            </label>
          )}
          {group === 'leaders' && (
            <label>Level
              <select value={level} onChange={(e) => setLevel(e.target.value)}>
                {LEADER_LEVELS.map(([k, l]) => <option key={k} value={k}>Only {l}</option>)}
              </select>
            </label>
          )}
          {group === 'members' && target === 'ward' && <label>Ward<select value={ward} onChange={(e) => setWard(e.target.value)}>{Array.from({ length: 250 }, (_, i) => <option key={i + 1} value={i + 1}>{wardLabel(i + 1)}</option>)}</select></label>}
          {(needsDistrict || group === 'block' || (group === 'leaders' && level !== 'gs')) && <label>District{needsDistrict ? '' : ' (optional)'}
            <select value={zone} onChange={(e) => setZone(e.target.value)} required={needsDistrict}>
              <option value="">{needsDistrict ? 'Choose…' : 'All districts'}</option>
              {districts.map((d) => <option key={d} value={d}>{d}</option>)}
            </select></label>}
          <label>Ready message
            <select value={template} onChange={(e) => pickTemplate(e.target.value)}>
              <option value="">Write my own</option>
              {TEMPLATES.map((t, i) => <option key={t[0]} value={i}>{t[0]}</option>)}
            </select>
          </label>
          <label>Title (optional)<input type="text" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="INC Delhi" /></label>
          <label style={{ gridColumn: '1 / -1' }}>Message (Hindi)<textarea rows="3" value={body} onChange={(e) => setBody(e.target.value)} required placeholder="आज के टास्क रात 11:59 तक पूरे करें" /></label>
          <div><button className="btn" type="submit" disabled={sending}>{sending ? 'Sending…' : 'Send now'}</button></div>
        </form>
        {result && <div className={'notice' + (result.ok ? ' ok' : '')} style={{ marginTop: 12 }}>{result.text}</div>}
      </section>

      <section className="card">
        <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', gap: 12, alignItems: 'center' }}>
          <div>
            <h2>Sent notifications</h2>
            <p className="sub">Day by day, with how many went out each day.</p>
          </div>
          <div className="filters">
            <label htmlFor="ndate">Date</label>
            <input id="ndate" type="date" value={date} max={istToday()} onChange={(e) => setDate(e.target.value)} />
            <button className={'btn sm ' + (date ? 'ghost' : '')} onClick={() => setDate('')}>All dates</button>
          </div>
        </div>
        {days.map(([d, list]) => (
          <div key={d} style={{ marginTop: 14 }}>
            <h3 className="daybar">{dayLabel(d)}{d === istToday() ? ' · Today' : ''}<span>{list.length} sent</span></h3>
            <div className="tbl">
              <table>
                <thead><tr><th style={{ width: 90 }}>Time</th><th style={{ width: 240 }}>To</th><th>Message</th><th style={{ width: 130 }}>Reached</th></tr></thead>
                <tbody>
                  {list.map((a) => (
                    <tr key={a.id}><td className="muted" style={{ whiteSpace: 'nowrap' }}>{new Date(a.created_at).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit' })}</td>
                      <td>{toLabel(a)}</td><td>{a.title && <b>{a.title}: </b>}{a.body}</td>
                      <td className="muted">{a.sent_count != null ? `${fmt(a.sent_count)} people` : a.pushed_count != null ? 'Instant push' : 'At next check-in'}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ))}
        {!days.length && <p className="muted">{date ? `Nothing was sent on ${dayLabel(date)}.` : 'Nothing sent yet.'}</p>}
      </section>
    </>
  );
}
