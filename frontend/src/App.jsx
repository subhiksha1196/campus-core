import { useEffect, useState } from 'react';
import { api } from './api.js';
import { useUser, setUser } from './session.js';
import { Avatar, Brand, titleCase } from './components/ui.jsx';
import LandingPage from './pages/LandingPage.jsx';
import AuthPage from './pages/AuthPage.jsx';
import ProfilePage from './pages/ProfilePage.jsx';
import NotificationsPage from './pages/NotificationsPage.jsx';
import ShortenerPage from './pages/ShortenerPage.jsx';
import CrawlerPage from './pages/CrawlerPage.jsx';
import MetricsPage from './pages/MetricsPage.jsx';
import CoursesPage from './pages/CoursesPage.jsx';
import TimetablePage from './pages/TimetablePage.jsx';
import AttendancePage from './pages/AttendancePage.jsx';
import GradesPage from './pages/GradesPage.jsx';
import FacultyPage from './pages/FacultyPage.jsx';

// Navigation. `roles` limits who sees an item; no roles means everyone.
const NAV = [
  { key: 'courses', label: 'Courses', icon: 'fa-book-open', page: CoursesPage, group: 'Academics' },
  { key: 'timetable', label: 'Timetable', icon: 'fa-calendar-days', page: TimetablePage, group: 'Academics', roles: ['STUDENT', 'FACULTY'] },
  { key: 'attendance', label: 'Attendance', icon: 'fa-clipboard-user', page: AttendancePage, group: 'Academics', roles: ['STUDENT'] },
  { key: 'grades', label: 'Grades', icon: 'fa-award', page: GradesPage, group: 'Academics', roles: ['STUDENT'] },
  { key: 'faculty', label: 'Course Manager', icon: 'fa-chalkboard-user', page: FacultyPage, group: 'Teaching', roles: ['FACULTY', 'ADMIN'], hideInSidebar: true },
  { key: 'notifications', label: 'Notifications', icon: 'fa-bell', page: NotificationsPage, group: 'Campus', hideInSidebar: true },
  { key: 'shortener', label: 'Link Shortener', icon: 'fa-link', page: ShortenerPage, group: 'Tools', roles: ['FACULTY', 'ADMIN'] },
  { key: 'crawler', label: 'Opportunity Crawler', icon: 'fa-spider', page: CrawlerPage, group: 'Tools', roles: ['FACULTY', 'ADMIN'] },
  { key: 'metrics', label: 'System Metrics', icon: 'fa-chart-line', page: MetricsPage, group: 'Tools', roles: ['FACULTY', 'ADMIN'] },
  { key: 'profile', label: 'My Profile', icon: 'fa-user', page: ProfilePage, group: 'Account', hideInSidebar: true },
];

const today = new Date().toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });

// Below this width the sidebar is an off-canvas drawer (see styles.css); above it, it can be collapsed.
const NARROW = '(max-width: 1020px)';
function useNarrow() {
  const [narrow, setNarrow] = useState(() => window.matchMedia(NARROW).matches);
  useEffect(() => {
    const m = window.matchMedia(NARROW);
    const on = (e) => setNarrow(e.matches);
    m.addEventListener('change', on);
    return () => m.removeEventListener('change', on);
  }, []);
  return narrow;
}

export default function App() {
  const user = useUser();
  const [ready, setReady] = useState(false);
  const [view, setView] = useState('landing'); // landing | login | register (only used when signed out)
  const [tab, setTab] = useState('courses');
  const [courseId, setCourseId] = useState(null); // course opened from Courses / Search, shown in Course Manager
  const [navOpen, setNavOpen] = useState(false);
  const narrow = useNarrow();
  const [collapsed, setCollapsed] = useState(() => { try { return localStorage.getItem('sis.navCollapsed') === '1'; } catch { return false; } });
  const setSidebar = (open) => {
    if (narrow) { setNavOpen(open); return; }
    setCollapsed(!open);
    try { localStorage.setItem('sis.navCollapsed', open ? '0' : '1'); } catch { /* preference just won't persist */ }
  };
  const [unread, setUnread] = useState(0);

  useEffect(() => {
    if (!sessionStorage.getItem('sis.token')) { setUser(null); setReady(true); return; }
    api.get('/api/auth/me').then(setUser).catch(() => setUser(null)).finally(() => setReady(true));
  }, []);

  useEffect(() => {
    if (!user) return;
    api.get(`/api/notifications/unread-count?studentId=${encodeURIComponent(user.id)}`)
      .then((d) => setUnread(d.unread || 0))
      .catch(() => {});
  }, [user, tab]);

  useEffect(() => {
    if (!navOpen) return;
    const close=e=>{if(e.key==='Escape')setNavOpen(false);};
    document.addEventListener('keydown',close);
    return ()=>document.removeEventListener('keydown',close);
  },[navOpen]);

  if (!ready) return <div className="boot"><Brand large /><span className="spinner" /></div>;

  if (!user) {
    if (view === 'login' || view === 'register') {
      return <AuthPage mode={view} onMode={setView} onBack={() => setView('landing')} />;
    }
    return <LandingPage onLogin={() => setView('login')} onRegister={() => setView('register')} />;
  }

  const items = NAV.filter((n) => !n.roles || n.roles.includes(user.role));
  const sideItems = items.filter((n) => !n.hideInSidebar); // still reachable from the bell in the top bar
  const groups = [...new Set(sideItems.map((n) => n.group))];
  const current = items.find((n) => n.key === tab) || items[0];
  const Page = current.page;
  const activeKey = current.key === 'faculty' ? 'courses' : current.key; // managing a course is part of Courses

  const go = (key) => { setTab(key); setNavOpen(false); window.scrollTo({ top: 0 }); };
  const openCourse = (id) => { setCourseId(id); setTab(id ? 'faculty' : 'courses'); setNavOpen(false); window.scrollTo({ top: 0 }); }; // no id = back to the Courses home
  const signOut = async () => {
    try { await api.post('/api/auth/logout'); } catch { /* token may already be invalid */ }
    sessionStorage.removeItem('sis.token');
    setUser(null);
    setTab('courses');
    setCourseId(null);
    setView('landing');
  };

  const hidden = !narrow && collapsed;
  return (
    <div className={`shell ${current.key === 'courses' ? 'course-shell' : ''}${hidden ? ' nav-collapsed' : ''}`}>
      <a className="skip-link" href="#main-content">Skip to content</a>
      {hidden && (
        <button type="button" className="icon-btn side-open" aria-label="Open sidebar" aria-expanded="false" aria-controls="sidebar" onClick={() => setSidebar(true)}><i className="fa-solid fa-bars" /></button>
      )}
      <aside id="sidebar" className={`sidebar${navOpen ? ' open' : ''}`}>
        <Brand />
        <button type="button" className="icon-btn side-toggle" aria-label="Close sidebar" aria-expanded="true" aria-controls="sidebar" onClick={() => setSidebar(false)}><i className="fa-solid fa-angles-left" /></button>
        <button className="icon-btn close-nav" aria-label="Close menu" onClick={()=>setNavOpen(false)}><i className="fa-solid fa-xmark"/></button>
        <nav aria-label="Main">
          {groups.map((g) => (
            <div key={g}>
              <div className="nav-label">{g}</div>
              {sideItems.filter((n) => n.group === g).map((n) => (
                <button key={n.key} className={`nav-item${n.key === activeKey ? ' active' : ''}`} aria-current={n.key === activeKey ? 'page' : undefined} onClick={() => go(n.key)}>
                  <i className={`fa-solid ${n.icon}`} />
                  {n.label}
                </button>
              ))}
            </div>
          ))}
        </nav>
        <div className="side-foot">
          <div className="user-card">
            <button type="button" onClick={() => go('profile')} title="Open my profile" aria-label="Open my profile" aria-current={current.key === 'profile' ? 'page' : undefined}
              style={{ display: 'flex', alignItems: 'center', gap: 12, flex: 1, minWidth: 0, background: 'none', border: 0, padding: 0, margin: 0, color: 'inherit', font: 'inherit', textAlign: 'left', cursor: 'pointer' }}>
              <Avatar name={user.name} size={40} />
              <div className="who"><strong>{user.name}</strong><small>{titleCase(user.role)}</small></div>
            </button>
            <button className="icon-btn" onClick={signOut} title="Sign out" aria-label="Sign out"><i className="fa-solid fa-right-from-bracket" /></button>
          </div>
        </div>
      </aside>
      <div className={`scrim${navOpen ? ' show' : ''}`} onClick={() => setNavOpen(false)} />

      <div className="main">
        <header className="topbar">
          <button className="icon-btn menu-btn" aria-expanded={navOpen} onClick={() => setNavOpen(true)} aria-label="Open menu"><i className="fa-solid fa-bars" /></button>
          <div className="crumbs">CampusCore<i className="fa-solid fa-chevron-right" /><b>{current.label}</b></div>
          <div className="spacer" />
          <span className="date-chip"><i className="fa-regular fa-calendar" />{today}</span>
          <button className="icon-btn" onClick={() => go('notifications')} title="Notifications" aria-label="Notifications">
            <i className="fa-regular fa-bell" />
            {unread > 0 && <span className="dot">{unread > 99 ? '99+' : unread}</span>}
          </button>
        </header>
        <main className="content" id="main-content">
          <div className="page" key={current.key}>
            <Page courseId={courseId} onOpenCourse={openCourse} onNavigate={go} />
          </div>
        </main>
      </div>
    </div>
  );
}
