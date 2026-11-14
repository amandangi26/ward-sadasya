import React, { useEffect, useMemo, useState } from 'react';
import { supabase, fetchAll, fmt, timeAgo, wardLabel, istDate } from './lib.js';
import { BarChart3, MessageCircle } from './icons.jsx';

// Topics the analysis looks for, by Hindi and English keywords.
const TOPICS = [
  ['पानी', 'Water', ['पानी', 'water', 'जल', 'टैंकर', 'सीवर', 'sewer', 'नाली', 'drain']],
  ['सड़क', 'Roads', ['सड़क', 'सडक', 'road', 'गड्ढ', 'pothole', 'गली', 'फुटपाथ']],
  ['बिजली', 'Electricity', ['बिजली', 'light', 'electric', 'बिल', 'bill', 'लाइट']],
  ['सफ़ाई', 'Garbage and cleaning', ['कूड़ा', 'कचरा', 'सफाई', 'सफ़ाई', 'garbage', 'waste', 'गंदगी', 'dustbin']],
  ['प्रदूषण', 'Pollution', ['प्रदूषण', 'pollution', 'धुआं', 'smog', 'हवा', 'aqi']],
  ['स्वास्थ्य', 'Health', ['अस्पताल', 'hospital', 'दवा', 'डॉक्टर', 'doctor', 'clinic', 'मोहल्ला', 'डेंगू', 'dengue', 'health']],
  ['शिक्षा', 'Education', ['स्कूल', 'school', 'फीस', 'fees', 'टीचर', 'शिक्षा', 'education']],
  ['पार्टी और संगठन', 'Party and organisation', ['पार्टी', 'संगठन', 'कार्यकर्ता', 'ब्लॉक', 'अध्यक्ष', 'ऑब्ज़र्वर', 'observer', 'party', 'टिकट', 'ticket', 'चुनाव', 'election']],
  ['ऐप', 'The app', ['ऐप', 'app', 'अपलोड', 'upload', 'अंक', 'points', 'लॉगिन', 'login', 'वीडियो', 'video']],
  ['नौकरी और रोज़गार', 'Jobs and livelihood', ['नौकरी', 'रोजगार', 'रोज़गार', 'job', 'वेंडर', 'रेहड़ी', 'दुकान', 'महंगाई', 'price']],
];
const NEG = ['समस्या', 'परेशान', 'नाराज', 'नाराज़', 'शिकायत', 'गुस्सा', 'खराब', 'ख़राब', 'नहीं मिल', 'धोखा', 'बंद', 'problem', 'angry', 'bad', 'issue', 'complaint', 'not working'];
const POS = ['धन्यवाद', 'शुक्रिया', 'अच्छा', 'बधाई', 'साथ हैं', 'thanks', 'thank', 'good', 'great', 'सुझाव', 'suggest'];

/** Topic and tone for one message, from its text (voice notes need listening). */
export function analyse(text) {
  const t = String(text || '').toLowerCase();
  const topics = TOPICS.filter(([, , words]) => words.some((w) => t.includes(w.toLowerCase()))).map(([hi]) => hi);
  const neg = NEG.filter((w) => t.includes(w)).length, pos = POS.filter((w) => t.includes(w)).length;
  return { topics: topics.length ? topics : [t ? 'अन्य' : 'वॉइस नोट'], tone: !t ? 'voice' : neg > pos ? 'concern' : pos > neg ? 'positive' : 'neutral' };
}
const ADMIN_TAGS = [['', 'Auto'], ['critical', 'Critical'], ['concern', 'Concern / problem'], ['negative', 'Negative'], ['positive', 'Positive'], ['neutral', 'Neutral']];
const TONE = { critical: ['Critical', '#B42318'], negative: ['Negative', '#9A4A0B'], concern: ['Concern', '#B42318'], positive: ['Positive', '#12884A'], neutral: ['Neutral', '#5B6270'], voice: ['Voice note', '#1B4FA8'] };

function Bars({ items }) {
  const max = Math.max(1, ...items.map((x) => x[1]));
  return items.map(([k, v]) => (
    <div key={k} style={{ display: 'grid', gridTemplateColumns: '150px 1fr 40px', gap: 8, alignItems: 'center', fontSize: 13, margin: '4px 0' }}>
      <span>{k}</span><div style={{ background: '#EEF0F4', borderRadius: 6, height: 10 }}><div style={{ width: `${(v / max) * 100}%`, background: '#1B4FA8', height: 10, borderRadius: 6 }} /></div><b>{v}</b>
    </div>));
}

export default function ToPresident() {
  const [rows, setRows] = useState([]);
  const [files, setFiles] = useState({});
  const [topic, setTopic] = useState('all');
  useEffect(() => { (async () => {
    const list = await fetchAll('president_messages', '*', (q) => q.order('created_at', { ascending: false }));
    setRows(list);
    const paths = list.slice(0, 150).flatMap((r) => [r.audio_path, ...(r.media || []).map((m) => m.path)]).filter(Boolean);
    if (paths.length) {
      const { data } = await supabase.storage.from('uploads').createSignedUrls(paths, 3600);
      setFiles(Object.fromEntries((data || []).filter((d) => d.signedUrl).map((d) => [d.path, d.signedUrl])));
    }
  })(); }, []);

  // The admin's own tag wins over the automatic one, and is what the President sees in his app.
  const tagged = useMemo(() => rows.map((r) => { const a = analyse(r.text); return { ...r, ...a, tone: r.admin_tag || a.tone }; }), [rows]);
  const setTag = async (r, tag) => {
    await supabase.from('president_messages').update({ admin_tag: tag || null }).eq('id', r.id);
    setRows((xs) => xs.map((x) => (x.id === r.id ? { ...x, admin_tag: tag || null } : x)));
  };
  const counts = (f) => Object.entries(tagged.reduce((a, r) => { for (const k of [].concat(f(r))) a[k] = (a[k] || 0) + 1; return a; }, {})).sort((a, b) => b[1] - a[1]);
  const days = [...Array(14)].map((_, i) => istDate(new Date(Date.now() - (13 - i) * 86400000).toISOString()));
  const perDay = days.map((d) => [d.slice(5), tagged.filter((r) => istDate(r.created_at) === d).length]);
  const week = tagged.filter((r) => Date.now() - new Date(r.created_at).getTime() < 7 * 86400000);
  const list = tagged.filter((r) => topic === 'all' || r.topics.includes(topic));

  return (
    <>
      <section className="kpis">
        <div className="card kpi"><div className="l">All messages</div><div className="v">{fmt(rows.length)}</div></div>
        <div className="card kpi"><div className="l">This week</div><div className="v">{fmt(week.length)}</div></div>
        <div className="card kpi"><div className="l">Concerns this week</div><div className="v">{fmt(week.filter((r) => r.tone === 'concern').length)}</div></div>
        <div className="card kpi"><div className="l">Voice notes</div><div className="v">{fmt(rows.filter((r) => r.audio_path).length)}</div><div className="s">Listen to these: the analysis reads text only</div></div>
      </section>
      <section className="card">
        <h2 style={{ display: 'flex', alignItems: 'center', gap: 8 }}><BarChart3 size={18} />What people are writing about</h2>
        <p className="sub">Messages to the DPCC President from ward members and observers, sorted into topics and tone by keywords. Set your own tag on any message; the President sees that tag in his app.</p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(300px,1fr))', gap: 20, marginTop: 10 }}>
          <div><b>Topics</b><Bars items={counts((r) => r.topics)} /></div>
          <div><b>Tone</b><Bars items={counts((r) => TONE[r.tone][0])} /></div>
          <div><b>By district</b><Bars items={counts((r) => r.district || 'Not known').slice(0, 14)} /></div>
          <div><b>Last 14 days</b><Bars items={perDay} /></div>
        </div>
      </section>
      <section className="card">
        <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'space-between', gap: 10 }}>
          <h2 style={{ display: 'flex', alignItems: 'center', gap: 8 }}><MessageCircle size={18} />Messages</h2>
          <select value={topic} onChange={(e) => setTopic(e.target.value)} style={{ maxWidth: 240 }}>
            <option value="all">All topics</option>{counts((r) => r.topics).map(([k, v]) => <option key={k} value={k}>{k} ({v})</option>)}
          </select>
        </div>
        {list.map((r) => (
          <div key={r.id} style={{ borderTop: '1px solid #E3E6EB', padding: '12px 0' }}>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
              <b>{r.sender_name || r.mobile}</b>
              <span className="muted" style={{ fontSize: 12.5 }}>{r.mobile} · {r.sender_role === 'member' ? (r.ward_no ? wardLabel(r.ward_no) : 'Member') : 'Observer'}{r.assembly ? ' · ' + r.assembly : ''}{r.district ? ' · ' + r.district : ''} · {timeAgo(r.created_at)}</span>
              <span className="tag" style={{ color: TONE[r.tone][1] }}>{TONE[r.tone][0]}</span>
              {r.topics.map((t) => <span key={t} className="tag mute">{t}</span>)}
              <select value={r.admin_tag || ''} onChange={(e) => setTag(r, e.target.value)} style={{ marginLeft: 'auto', padding: '4px 8px', fontSize: 12.5 }} aria-label="Tag shown to the President">
                {ADMIN_TAGS.map(([k, l]) => <option key={k} value={k}>Tag: {l}</option>)}
              </select>
            </div>
            {r.text && <p style={{ margin: '6px 0' }}>{r.text}</p>}
            {r.audio_path && files[r.audio_path] && <audio controls src={files[r.audio_path]} style={{ width: 280 }} />}
            {(r.media || []).length > 0 && <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 6 }}>
              {r.media.map((m) => files[m.path] && (m.kind === 'video'
                ? <video key={m.path} src={files[m.path]} controls style={{ width: 180, borderRadius: 8 }} />
                : <a key={m.path} href={files[m.path]} target="_blank" rel="noreferrer"><img src={files[m.path]} alt="" style={{ width: 120, height: 90, objectFit: 'cover', borderRadius: 8 }} /></a>))}
            </div>}
          </div>))}
        {!list.length && <p className="muted">No messages yet.</p>}
      </section>
    </>
  );
}
