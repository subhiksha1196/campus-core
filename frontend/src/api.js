import { setUser } from './session.js';
function identity() {
  const token = sessionStorage.getItem('sis.token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}
async function request(path, method='GET', body) {
  const res = await fetch(path, {method, headers: {'Content-Type':'application/json', ...identity()}, body: body === undefined ? undefined : JSON.stringify(body)});
  if (!res.ok) {
    if (res.status === 401 && !path.endsWith('/login')) { sessionStorage.removeItem('sis.token'); setUser(null); }
    const error = await res.json().catch(()=>({message:res.statusText}));
    throw Object.assign(new Error(error.message || res.statusText), {status: res.status});
  }
  return res.status === 204 ? null : res.json();
}
export const api = {get:p=>request(p),post:(p,b)=>request(p,'POST',b),put:(p,b)=>request(p,'PUT',b),patch:(p,b)=>request(p,'PATCH',b),del:p=>request(p,'DELETE')};
