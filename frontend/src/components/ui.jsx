/* Shared building blocks for the CampusCore UI. Icons come from Font Awesome (loaded in index.html). */
import { useTheme } from '../theme.js';

export const initials = (name = '') =>
  name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join('') || '?';

export const titleCase = (s = '') => s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();

export const firstName = (name = '') => name.split(/\s+/).filter(Boolean)[0] || 'there';

export function Brand({ light, large, onClick }) {
  const cls = `brand${light ? ' brand-light' : ''}${large ? ' brand-lg' : ''}`;
  const inner = (
    <>
      <span className="brand-mark" aria-hidden="true"><i/><i/><i/><i/></span>
      <span className="brand-name">Campus<b>Core</b></span>
    </>
  );
  return onClick
    ? <button type="button" className={cls} onClick={onClick} aria-label="CampusCore home">{inner}</button>
    : <span className={cls}>{inner}</span>;
}

export function Avatar({ name, size = 40 }) {
  return (
    <span className="avatar" style={{ width: size, height: size, fontSize: Math.round(size * 0.38) }} aria-hidden="true">
      {initials(name)}
    </span>
  );
}

/** Light / dark switch. */
export function ThemeToggle({ className = '' }) {
  const [theme, toggle] = useTheme();
  const dark = theme === 'dark';
  const label = dark ? 'Switch to light mode' : 'Switch to dark mode';
  return (
    <button type="button" className={`icon-btn ${className}`} onClick={toggle} title={label} aria-label={label}>
      <i className={`fa-solid ${dark ? 'fa-sun' : 'fa-moon'}`} />
    </button>
  );
}

export function PageHead({ icon, title, sub, children }) {
  return (
    <div className="page-head">
      <div className="page-head-text">
        <span className="page-head-ico"><i className={`fa-solid ${icon}`} /></span>
        <div>
          <h1>{title}</h1>
          {sub && <p>{sub}</p>}
        </div>
      </div>
      {children && <div className="page-head-actions">{children}</div>}
    </div>
  );
}

const ALERT_ICON = {
  error: 'fa-circle-exclamation',
  success: 'fa-circle-check',
  info: 'fa-circle-info',
  warning: 'fa-triangle-exclamation',
};
export function Alert({ kind = 'error', children }) {
  return (
    <div className={`alert alert-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      <i className={`fa-solid ${ALERT_ICON[kind]}`} />
      <div>{children}</div>
    </div>
  );
}

export function Empty({ icon = 'fa-inbox', title, children }) {
  return (
    <div className="empty">
      <div className="empty-ico"><i className={`fa-solid ${icon}`} /></div>
      {title && <h4>{title}</h4>}
      {children && <p>{children}</p>}
    </div>
  );
}

export function Loading({ text = 'Loading…' }) {
  return <div className="loading"><span className="spinner" />{text}</div>;
}

export function Stat({ icon, tone = 'primary', value, label }) {
  return (
    <div className="stat">
      <span className={`stat-ico tone-${tone}`}><i className={`fa-solid ${icon}`} /></span>
      <div><b>{value}</b><span className="lbl">{label}</span></div>
    </div>
  );
}

export function Badge({ tone, icon, children }) {
  return (
    <span className={`badge${tone ? ` badge-${tone}` : ''}`}>
      {icon && <i className={`fa-solid ${icon}`} />}
      {children}
    </span>
  );
}

export function Field({ label, hint, children }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

/** Input with a leading Font Awesome icon. */
export function IconInput({ icon, toggle, children }) {
  return (
    <span className={`input-wrap${toggle ? ' has-toggle' : ''}`}>
      <i className={`fa-solid ${icon}`} />
      {children}
    </span>
  );
}

/** Card with a titled header. `flush` removes padding so tables sit edge to edge. */
export function Card({ icon, title, sub, flush, actions, children }) {
  return (
    <section className={`card${flush ? ' flush' : ''}`}>
      {(title || actions) && (
        <div className="card-head">
          <h2 className="card-title">{icon && <i className={`fa-solid ${icon}`} />}{title}</h2>
          {actions && <div className="chips">{actions}</div>}
        </div>
      )}
      {sub && <p className="card-sub">{sub}</p>}
      {children}
    </section>
  );
}

/** Underlined tab strip. items: [{ key, label }] */
export function Tabs({ items, value, onChange }) {
  return (
    <div className="tabs" role="tablist">
      {items.map((t) => (
        <button key={t.key} type="button" role="tab" aria-selected={t.key === value} className={t.key === value ? 'active' : ''} onClick={() => onChange(t.key)}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

/** Horizontal progress bar. `mark` draws a tick (for example the minimum attendance). */
export function Meter({ pct = 0, mark, low, small }) {
  const w = Math.min(100, Math.max(0, Number(pct) || 0));
  return (
    <div className={`meter${low ? ' low' : ''}${small ? ' sm' : ''}`} role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(w)}>
      <i style={{ width: `${w}%` }} />
      {mark != null && <u style={{ left: `${mark}%` }} />}
    </div>
  );
}

export const TableWrap = ({ children }) => <div className="table-wrap">{children}</div>;

export const roleTone = (role) => (role === 'ADMIN' ? 'danger' : role === 'FACULTY' ? 'accent' : 'success');

/** Stable colour class for a course code (used on the timetable). */
export const slotClass = (code = '') => `s${[...code].reduce((a, c) => a + c.charCodeAt(0), 0) % 6}`;
