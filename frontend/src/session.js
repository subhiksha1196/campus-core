import { useEffect, useReducer } from 'react';

// Who is acting: { id, name, role: STUDENT | FACULTY | ADMIN }. Stored in the browser.
// Person 1's login can call setUser() with the logged-in person instead of the picker.
const KEY = 'sis.user';
let user = null;
try {
  user = JSON.parse(sessionStorage.getItem(KEY));
} catch {
  user = null;
}
const listeners = new Set();

export function setUser(u) {
  user = u;
  if (u) sessionStorage.setItem(KEY, JSON.stringify(u));
  else sessionStorage.removeItem(KEY);
  listeners.forEach((l) => l());
}

export function useUser() {
  const [, bump] = useReducer((x) => x + 1, 0);
  useEffect(() => {
    listeners.add(bump);
    return () => listeners.delete(bump);
  }, []);
  return user;
}
