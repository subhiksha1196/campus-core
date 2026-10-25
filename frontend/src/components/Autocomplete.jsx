import { useEffect, useId, useRef, useState } from 'react';
import { api } from '../api.js';

/** Shared, keyboard-accessible suggestions. Local options or the academic search API. */
export default function Autocomplete({ type='course', value='', onText, onPick, onResults,
  placeholder, label='Search', limit=7, options, multiple=false, selectValue, required=false }) {
  const [items,setItems]=useState([]),[open,setOpen]=useState(false),[active,setActive]=useState(-1);
  const [message,setMessage]=useState('');
  const timer=useRef(),seq=useRef(0),focused=useRef(false),id=useId();
  const callbacks=useRef({onResults});callbacks.current={onResults};
  useEffect(()=>()=>{clearTimeout(timer.current);seq.current++;},[]);
  useEffect(()=>{seq.current++;clearTimeout(timer.current);setItems([]);setOpen(false);},[type]);
  function query(text) {
    clearTimeout(timer.current);const ticket=++seq.current;
    const q=(multiple?text.split(',').pop():text).trim();
    setItems([]);setActive(-1);setMessage('');
    if(!q){setOpen(false);callbacks.current.onResults?.(null);return;}
    setOpen(true);setMessage('Searching…');
    timer.current=setTimeout(async()=>{
      const start=performance.now();
      try {
        const data=options ? {results:options.filter(x=>`${x.label} ${x.sub||''} ${x.id}`.toLowerCase().includes(q.toLowerCase())).slice(0,limit),tookMs:0}
          : await api.get(`/api/academic/search?type=${encodeURIComponent(type)}&q=${encodeURIComponent(q)}&limit=${limit}`);
        if(ticket!==seq.current)return;
        setItems(data.results);setActive(-1);setMessage(data.results.length?'':'No matching results.');
        setOpen(focused.current);callbacks.current.onResults?.({...data,rtt:performance.now()-start});
      }catch(e){if(ticket===seq.current){setItems([]);setMessage(e.message||'Search unavailable. Try again.');}}
    },180);
  }
  function pick(index) {
    const item=items[index];if(!item)return;
    ++seq.current;clearTimeout(timer.current);
    const chosen=selectValue?selectValue(item):item.label;
    const previous=multiple?value.split(',').slice(0,-1).map(x=>x.trim()).filter(Boolean):[];
    const next=multiple?[...new Set([...previous,chosen])].join(', ')+', ':chosen;
    onText?.(next);setOpen(false);setActive(-1);
    if(!options)api.post('/api/academic/search/feedback',{rank:index+1}).catch(()=>{});
    onPick?.(item,index+1);
  }
  function key(e) {
    if(e.key==='Escape'){++seq.current;clearTimeout(timer.current);setOpen(false);return;}
    if(!open||!items.length)return;
    if(e.key==='ArrowDown'){e.preventDefault();setActive(n=>(n+1)%items.length);}
    else if(e.key==='ArrowUp'){e.preventDefault();setActive(n=>(n<=0?items.length:n)-1);}
    else if(e.key==='Enter'&&active>=0){e.preventDefault();pick(active);}
  }
  return <div className="ac">
    <input role="combobox" aria-label={label} aria-autocomplete="list" aria-expanded={open}
      aria-controls={`${id}-list`} aria-activedescendant={open&&active>=0?`${id}-${active}`:undefined}
      value={value} required={required} placeholder={placeholder} autoComplete="off"
      onChange={e=>{onText?.(e.target.value);query(e.target.value);}} onKeyDown={key}
      onFocus={()=>{focused.current=true;query(value);}} onBlur={()=>{focused.current=false;setOpen(false);}}/>
    {open&&<div className="ac-list"><div role="listbox" id={`${id}-list`} aria-label={`${label} suggestions`}>
      {items.map((item,i)=><div role="option" aria-selected={active===i} id={`${id}-${i}`} key={item.id}
        className={`ac-item ${active===i?'active':''}`} onMouseDown={e=>e.preventDefault()} onClick={()=>pick(i)} onMouseMove={()=>setActive(i)}>
        <span><strong>{item.label}</strong><small>{item.sub}</small></span><small>{item.id}</small>
      </div>)}
    </div>{message&&<div className="ac-none" role="status">{message}</div>}</div>}
  </div>;
}
