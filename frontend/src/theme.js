import { useEffect, useReducer } from 'react';

// Light / dark theme. The choice is saved in the browser and defaults to the operating system setting.
// index.html sets data-theme before first paint so there is no flash.
const KEY = 'cc.theme';
const listeners = new Set();

const system = () =>
  window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';

export function currentTheme() {
  const attr = document.documentElement.getAttribute('data-theme');
  if (attr === 'light' || attr === 'dark') return attr;
  try {
    const saved = localStorage.getItem(KEY);
    if (saved === 'light' || saved === 'dark') return saved;
  } catch { /* storage unavailable */ }
  return system();
}

export function setTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  try { localStorage.setItem(KEY, theme); } catch { /* storage unavailable */ }
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', theme === 'dark' ? '#0e1210' : '#ffffff');
  listeners.forEach((l) => l());
}

export function useTheme() {
  const [, bump] = useReducer((x) => x + 1, 0);
  useEffect(() => {
    listeners.add(bump);
    return () => listeners.delete(bump);
  }, []);
  const theme = currentTheme();
  return [theme, () => setTheme(theme === 'dark' ? 'light' : 'dark')];
}
