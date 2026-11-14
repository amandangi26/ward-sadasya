import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';
import { supabase, loginId } from './lib.js';
import { Lock, User, Eye, EyeOff, ShieldCheck, ArrowRight, Download, AlertCircle } from 'lucide-react';
import Dashboard from './Dashboard.jsx';
import Tasks from './Tasks.jsx';
import Members from './Members.jsx';
import Messages from './Messages.jsx';
import Reminders from './Reminders.jsx';
import Calling from './Calling.jsx';
import Reports from './Reports.jsx';
import Team from './Team.jsx';
import Settings from './Settings.jsx';
import ProfileUpdates from './ProfileUpdates.jsx';
import Leaders from './Leaders.jsx';
import MemberTasks from './MemberTasks.jsx';
import ToPresident from './ToPresident.jsx';
import Posters from './Posters.jsx';
import DataIssues from './DataIssues.jsx';
import { TriangleAlert, BarChart3, ClipboardList, Users, UserCheck, Phone, Bell, MessageCircle, ImageIcon, SettingsIcon } from './icons.jsx';
import { FileText, ListChecks, Network, Video, LogOut } from 'lucide-react';

const APP_NAME = 'INC MCD NETWORK';

// Sidebar groups for the admin. Each entry: [id, label, icon].
const ADMIN_GROUPS = [
  ['Overview', [['dashboard', 'Analytics', BarChart3], ['reports', 'Reports', FileText]]],
  ['Field work', [['tasks', 'Tasks', ClipboardList], ['membertasks', 'Member tasks', ListChecks], ['leaders', 'Leaders', Network]]],
  ['People', [['members', 'Ward members', Users], ['profiles', 'Profile updates', UserCheck], ['calling', 'Calling', Phone], ['dataissues', 'Data discrepancies', TriangleAlert], ['team', 'Team', ShieldCheck]]],
  ['Communication', [['messages', 'Leader messages', Video], ['reminders', 'Notifications', Bell], ['topresident', 'Messages to President', MessageCircle], ['posters', 'Posters', ImageIcon]]],
  ['Setup', [['settings', 'Settings', SettingsIcon]]],
];
const ADMIN_TABS = ADMIN_GROUPS.flatMap(([, items]) => items);
const TELECALLER_TABS = [['calling', 'Calling list']];

function Login() {
  const [id, setId] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setErr('');
    setBusy(true);
    const targetEmail = loginId(id);
    const { error } = await supabase.auth.signInWithPassword({ email: targetEmail, password });
    setBusy(false);
    if (error) {
      if (error.message === 'Invalid login credentials') {
        setErr('गलत मोबाइल नंबर/ईमेल या पासवर्ड। कृपया जाँच कर पुनः प्रयास करें।');
      } else {
        setErr(error.message);
      }
    } else {
      if (window.location.pathname !== '/mcd') {
        window.history.replaceState(null, '', '/mcd');
      }
    }
  };

  return (
    <div className="login-page">
      <div className="login-tricolor-bar" />
      <div className="login-container">
        <div className="login-brand-header">
          <div className="login-logo-wrap">
            <img src="/logo.png" alt="Indian National Congress" className="login-logo" />
          </div>
          {/* <div className="">
            <span className="login-role-dot" />
            दिल्ली प्रदेश कांग्रेस कमेटी (DPCC)
          </div> */}
          <h1 className="login-title">{APP_NAME}</h1>
          {/* <p className="login-subtitle">MCD वार्ड प्रबंधन एवं कंट्रोल पोर्टल</p> */}
        </div>

        <div className="login-card">
          <div className="login-card-header">
            <h2>
              <ShieldCheck size={20} color="#1B4FA8" />
              अधिकृत प्रवेश (Authorized Login)
            </h2>
            <p>एडमिन एवं टेलीकॉलर्स अपने पंजीकृत क्रेडेंशियल्स से लॉग इन करें</p>
          </div>

          <form onSubmit={submit} className="login-form">
            <div className="login-field">
              <label className="login-label" htmlFor="loginId">
                मोबाइल नंबर या ईमेल (Mobile or Email)
              </label>
              <div className="login-input-wrap">
                <span className="login-input-icon">
                  <User size={18} />
                </span>
                <input
                  id="loginId"
                  type="text"
                  autoComplete="username"
                  placeholder="admin@domain.com"
                  value={id}
                  onChange={(e) => setId(e.target.value)}
                  className="login-input"
                  required
                />
              </div>
              <div className="login-hint">टेलीकॉलर साथी अपना 10 अंकों का पंजीकृत मोबाइल नंबर दर्ज करें</div>
            </div>

            <div className="login-field">
              <label className="login-label" htmlFor="loginPassword">
                पासवर्ड (Password)
              </label>
              <div className="login-input-wrap">
                <span className="login-input-icon">
                  <Lock size={18} />
                </span>
                <input
                  id="loginPassword"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  placeholder="••••••••••••"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="login-input"
                  required
                />
                <button
                  type="button"
                  className="login-pwd-toggle"
                  onClick={() => setShowPassword(!showPassword)}
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                  tabIndex={-1}
                >
                  {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </div>
            </div>

            {err && (
              <div className="login-error" role="alert">
                <AlertCircle size={18} style={{ flexShrink: 0, marginTop: 1 }} />
                <span>{err}</span>
              </div>
            )}

            <button className="login-btn" type="submit" disabled={busy}>
              {busy ? (
                <>प्रवेश हो रहा है… (Verifying…)</>
              ) : (
                <>
                  <span>पोर्टल में प्रवेश करें (Log In)</span>
                  <ArrowRight size={18} />
                </>
              )}
            </button>
          </form>

          <div className="login-extra-box">


            <a href="/download" className="login-apk-link">
              <Download size={15} />
              <span>Android App (v1.9 APK) डाउनलोड करें</span>
            </a>
          </div>
        </div>
      </div>

      <footer className="login-footer">
        <div>
          <b>{APP_NAME}</b> · दिल्ली प्रदेश कांग्रेस कमेटी (DPCC)
        </div>
        {/* <div>
          © {new Date().getFullYear()} Built by Coceptual Media
        </div> */}
      </footer>
    </div>
  );
}

function Footer() {
  return (
    <footer className="footer">
      <span><b>{APP_NAME}</b> · Delhi Pradesh Congress Committee</span>
      {/* <span>© {new Date().getFullYear()} · Built by Coceptual Media</span> */}
    </footer>
  );
}

function App() {
  const [session, setSession] = useState(undefined);
  const [me, setMe] = useState(null); // { userId, role, name, districts }
  const [tab, setTab] = useState(() => {
    try {
      const q = new URLSearchParams(window.location.search);
      return q.get('tab') || null;
    } catch (_) {
      return null;
    }
  });

  // Client-side routing: ensure root or legacy routes redirect to /mcd
  useEffect(() => {
    const p = window.location.pathname;
    if (p === '/' || p === '' || p === '/login' || p === '/admin') {
      const q = window.location.search;
      window.history.replaceState(null, '', `/mcd${q}`);
    }
  }, []);

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => setSession(data.session));
    const { data: sub } = supabase.auth.onAuthStateChange((_e, s) => setSession(s));
    return () => sub.subscription.unsubscribe();
  }, []);

  useEffect(() => {
    if (!session) { setMe(null); setTab(null); return; }
    (async () => {
      const userId = session.user.id;
      const { data: role } = await supabase.rpc('my_role');
      if (role === 'telecaller') {
        const { data: t } = await supabase.from('telecallers').select('name, districts').eq('user_id', userId).maybeSingle();
        setMe({ userId, role, name: t?.name || 'Telecaller', districts: t?.districts || [] });
        setTab('calling');
      } else if (role === 'admin') {
        const { data: a } = await supabase.from('admins').select('label').eq('user_id', userId).maybeSingle();
        setMe({ userId, role, name: a?.label || session.user.email, districts: [] });
        setTab((t) => t || 'dashboard');
      } else {
        setMe({ userId, role: null });
      }
    })();
  }, [session]);

  const changeTab = (newTab) => {
    setTab(newTab);
    const q = newTab === 'dashboard' ? '' : `?tab=${newTab}`;
    window.history.replaceState(null, '', `/mcd${q}`);
  };

  const handleSignOut = async () => {
    await supabase.auth.signOut();
    window.history.replaceState(null, '', '/mcd');
  };

  if (session === undefined) return null;
  if (!session) return <Login />;
  if (!me) {
    return (
      <div className="login-page">
        <div className="login-tricolor-bar" />
        <div className="login-container">
          <div className="login-card" style={{ textAlign: 'center', padding: 40 }}>
            <div className="login-spinner" />
            <p style={{ color: '#64748B', fontSize: 14, margin: 0 }}>डैशबोर्ड लोड हो रहा है…</p>
          </div>
        </div>
      </div>
    );
  }
  if (!me.role) {
    return (
      <div className="login-page">
        <div className="login-tricolor-bar" />
        <div className="login-container">
          <div className="login-card" style={{ textAlign: 'center', padding: '36px 28px' }}>
            <div style={{ color: '#DC2626', marginBottom: 14 }}>
              <AlertCircle size={44} style={{ margin: '0 auto' }} />
            </div>
            <h3 style={{ fontSize: 18, color: '#0F1B33', marginBottom: 8 }}>पहुँच की अनुमति नहीं है (No Access)</h3>
            <p style={{ fontSize: 13.5, color: '#64748B', lineHeight: 1.5, marginBottom: 20 }}>
              यह लॉगिन अभी सक्रिय नहीं है। एडमिन पैनल या कॉलिंग लिस्ट का उपयोग करने के लिए मास्टर एडमिन से संपर्क करें।
            </p>
            <button className="login-btn" onClick={handleSignOut}>
              लॉग आउट करें (Log out)
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <>
      <div className={'shell' + (me.role === 'admin' ? '' : ' solo')}>
      {me.role === 'admin' && (
        <aside className="sidebar" aria-label="Admin sections">
          <div className="brand">
            <img src="/logo.png" alt="Congress party symbol" />
            <div><b>{APP_NAME}</b><small>Command Dashboard</small></div>
          </div>
          <nav>
            {ADMIN_GROUPS.map(([group, items]) => (
              <div key={group} className="navgroup">
                <span>{group}</span>
                {items.map(([id, label, Icon]) => (
                  <button key={id} className={tab === id ? 'on' : ''} onClick={() => changeTab(id)}>
                    <Icon size={17} strokeWidth={2} />{label}
                  </button>
                ))}
              </div>
            ))}
          </nav>
          <div className="who">
            <span className="pill"><span className="dot" />{me.name}</span>
            <button onClick={handleSignOut}><LogOut size={15} />Log out</button>
          </div>
        </aside>
      )}
      <div className="content">
      <header className="topbar">
        <div>
          <h1>{me.role === 'admin' ? (ADMIN_TABS.find(([id]) => id === tab) || [])[1] : 'Calling Panel'}</h1>
          <small>Delhi Pradesh Congress Committee · {me.role === 'admin' ? '250 MCD wards' : (me.districts || []).join(', ')}</small>
        </div>
        {me.role !== 'admin' && (
          <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
            <span className="pill light"><span className="dot" />{me.name} · Telecaller</span>
            <button className="btn ghost" style={{ minHeight: 34, padding: '6px 12px' }} onClick={handleSignOut}>Log out</button>
          </div>
        )}
      </header>
      <main className="main">
        {tab === 'dashboard' && <Dashboard />}
        {tab === 'reports' && <Reports />}
        {tab === 'calling' && <Calling me={me} />}
        {tab === 'tasks' && <Tasks />}
        {tab === 'members' && <Members />}
        {tab === 'messages' && <Messages />}
        {tab === 'reminders' && <Reminders />}
        {tab === 'team' && <Team />}
        {tab === 'profiles' && <ProfileUpdates />}
        {tab === 'leaders' && <Leaders />}
        {tab === 'membertasks' && <MemberTasks />}
        {tab === 'topresident' && <ToPresident />}
        {tab === 'posters' && <Posters />}
        {tab === 'dataissues' && <DataIssues />}
        {tab === 'settings' && <Settings />}
      </main>
      <Footer />
      </div>
      </div>
    </>
  );
}

createRoot(document.getElementById('root')).render(<App />);
