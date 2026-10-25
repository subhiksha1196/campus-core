export const plural = (n, one, many) => `${n} ${n === 1 ? one : many || one + 's'}`;
export const pctText = (p) => (p == null ? '-' : `${Number(p).toFixed(1)}%`);
export const num = (n) => (n == null ? '' : String(parseFloat(Number(n).toFixed(2))));
export const ms = (v) => (v == null ? '-' : Number(v).toFixed(v < 1 ? 3 : 2));
export const todayIso = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);
