import React, { useEffect, useState } from 'react';
import { supabase, wardLabel, dayLabel } from './lib.js';

// Modal: every video and photo one member has uploaded, grouped by task.
export default function MemberUploads({ member, onClose }) {
  const [items, setItems] = useState(null);

  useEffect(() => {
    (async () => {
      const { data } = await supabase.from('media').select('*, tasks(title, task_date)').eq('mobile', member.mobile).order('created_at', { ascending: false });
      const rows = data || [];
      const paths = rows.map((r) => r.path);
      const signed = paths.length ? (await supabase.storage.from('uploads').createSignedUrls(paths, 3600)).data || [] : [];
      setItems(rows.map((r, i) => ({ ...r, url: signed[i]?.signedUrl })));
    })();
  }, [member.mobile]);

  return (
    <div className="modal" role="dialog" aria-modal="true" aria-label={'Uploads of ' + member.name} onClick={onClose}>
      <div className="card" onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start' }}>
          <div>
            <h2>{member.name}</h2>
            <p className="sub">{member.mobile} · {wardLabel(member.ward_no)} · {member.tasks_done ?? ''} tasks done</p>
          </div>
          <button className="btn ghost" onClick={onClose}>Close</button>
        </div>
        {items === null && <p className="muted">Loading…</p>}
        {items && !items.length && <p className="muted">No uploads yet.</p>}
        <div className="media-grid">
          {(items || []).map((m) => (
            <div key={m.id}>
              {m.url ? (
                m.kind === 'video'
                  ? <video src={m.url} controls preload="metadata" />
                  : <img src={m.url} alt={'Photo for ' + (m.tasks?.title || 'task')} />
              ) : (
                <div style={{ height: '140px', background: '#1E2433', borderRadius: '8px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', color: '#9CA3AF', padding: '12px', textAlign: 'center' }}>
                  <span style={{ fontSize: '26px', marginBottom: '4px' }}>📦</span>
                  <b style={{ fontSize: '12px', color: '#E5E7EB' }}>Media Archived</b>
                  <span style={{ fontSize: '11px' }}>Offline backup me saved hai</span>
                </div>
              )}
              <div style={{ fontSize: 12, marginTop: 4 }}><b>{m.tasks?.title}</b></div>
              <div className="muted" style={{ fontSize: 12 }}>{m.tasks ? dayLabel(m.tasks.task_date) : ''} · {new Date(m.created_at).toLocaleString('en-IN')}</div>
              {m.url ? (
                <a href={m.url} target="_blank" rel="noreferrer" style={{ fontSize: 12 }}>Open / download</a>
              ) : (
                <span className="muted" style={{ fontSize: 12 }}>Archived in backup</span>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
