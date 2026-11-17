import {useEffect,useRef,useState} from 'react';
import {appFetch} from './app-paths.mjs';
import {PolicyReader} from './policy-reader';
import type {PolicyEntry,PolicyDocument} from '../lib/policy-types';
const labels:Record<string,string>={pending:'待审核',approved:'确认正确',changes_requested:'需要修改',needs_verification:'待核实',stale:'内容更新 · 待复审'};
type Review={version:number;status:string;comment:string;reviewerName:string;createdAt:string;contentHash:string};
type Item={id:string;title:string;category:string;state:string;review:Review|null};
type Detail={policy:PolicyEntry;document:PolicyDocument;contentHash:string;review:Review|null;state:string;history:Review[]};
export function PolicyReviewPage({allowed,roleNames,onSources,onRole}:{allowed:boolean;roleNames:Record<string,string>;onSources:(refs:string[])=>void;onRole:(id:string)=>void}) {
 const [items,setItems]=useState<Item[]>([]),[selected,setSelected]=useState(''),[filter,setFilter]=useState(''),[query,setQuery]=useState('');
 const [detail,setDetail]=useState<Detail|null>(null),[status,setStatus]=useState('approved'),[comment,setComment]=useState('');
 const [error,setError]=useState(''),[notice,setNotice]=useState(''),[loading,setLoading]=useState(true),[saving,setSaving]=useState(false),[reload,setReload]=useState(0);
 const alive=useRef(false),generation=useRef(0);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;generation.current++;};},[]);
 useEffect(()=>{if(!allowed)return;const controller=new AbortController();setLoading(true);
  appFetch('/api/policy-reviews',{signal:controller.signal}).then(async r=>{const data=await r.json() as {items:Item[];error?:string};if(!r.ok)throw new Error(data.error||'读取审核清单失败');if(!controller.signal.aborted){setItems(data.items);setSelected(v=>v||data.items[0]?.id||'');}}).catch(e=>{if(!controller.signal.aborted)setError(e.message);}).finally(()=>{if(!controller.signal.aborted)setLoading(false);});return()=>controller.abort();
 },[allowed,reload]);
 useEffect(()=>{if(!allowed||!selected)return;const controller=new AbortController();const ticket=++generation.current;setDetail(null);setError('');setNotice('');
  appFetch(`/api/policy-reviews/${encodeURIComponent(selected)}`,{signal:controller.signal}).then(async r=>{const data=await r.json() as Detail & {error?:string};if(!r.ok)throw new Error(data.error||'读取制度失败');if(!controller.signal.aborted&&ticket===generation.current){setDetail(data);setStatus(data.review?.status||'approved');setComment(data.review?.comment||'');}}).catch(e=>{if(!controller.signal.aborted)setError(e.message);});return()=>controller.abort();
 },[allowed,selected,reload]);
 async function save(){if(!detail||saving)return;const ticket=generation.current;setSaving(true);setError('');setNotice('');
  try{const r=await appFetch(`/api/policy-reviews/${encodeURIComponent(selected)}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({status,comment,version:detail.review?.version??0,contentHash:detail.contentHash})});const data=await r.json() as Detail & {error?:string};if(!alive.current||ticket!==generation.current)return;if(!r.ok)throw new Error(data.error||'保存失败，请刷新核对记录后重试');setDetail(data);setItems(all=>all.map(i=>i.id===selected?{...i,state:data.state,review:data.review}:i));setNotice('审核已保存');
  }catch(e){if(alive.current&&ticket===generation.current)setError(e instanceof Error?e.message:'保存失败');}finally{if(alive.current)setSaving(false);}
 }
 if(!allowed)return <p className="section-placeholder">仅指定制度审核员可以使用此页面。</p>;
 const shown=items.filter(i=>(!filter||i.state===filter)&&(!query||`${i.title} ${i.category}`.includes(query)));
 return <div className="policy-review-page">
  <header className="review-header"><div><h1>制度审核</h1><p>逐项核对原文、适用对象和资料依据，记录结论与修改建议。审核结论不直接改写制度原文。</p></div><button className="text-button" disabled={saving} onClick={()=>setReload(v=>v+1)}>刷新记录</button></header>
  <div className="review-summary">{Object.entries(labels).map(([key,label])=><button key={key} className={filter===key?'selected':''} disabled={saving} onClick={()=>setFilter(filter===key?'':key)}>{label} <strong>{items.filter(i=>i.state===key).length}</strong></button>)}</div>
  <div className="review-layout"><aside className="review-index"><input aria-label="搜索待审制度" placeholder="搜索制度名称或分类" value={query} disabled={saving} onChange={e=>setQuery(e.target.value)}/>{loading&&<p role="status">读取清单中…</p>}{shown.map(i=><button className={selected===i.id?'current':''} key={i.id} disabled={saving} onClick={()=>setSelected(i.id)}><span>{i.title}</span><small>{i.category} · {labels[i.state]}</small></button>)}{!loading&&!shown.length&&<p>当前筛选下没有制度。</p>}</aside>
   <section className="review-content">{error&&<p className="form-error" role="alert">{error}</p>}{notice&&<p className="review-notice" role="status">{notice}</p>}{detail&&detail.policy.id===selected?<>
    <div className="review-state">审核状态：{labels[detail.state]}{detail.state==='stale'&&'。以下内容已更新，请重新核对。'}</div>
    <PolicyReader policy={detail.policy} document={detail.document??undefined} roleNames={roleNames} onSources={onSources} onRole={onRole}/>
    <form className="review-form" onSubmit={e=>{e.preventDefault();void save();}}><h2>填写审核结论</h2><label>审核结果<select aria-label="审核结果" value={status} disabled={saving} onChange={e=>setStatus(e.target.value)}><option value="approved">确认正确</option><option value="changes_requested">需要修改</option><option value="needs_verification">待核实</option></select></label><label>审核意见<textarea aria-label="审核意见" rows={5} maxLength={4000} required={status!=='approved'} disabled={saving} value={comment} onChange={e=>setComment(e.target.value)} placeholder="指出具体条款、问题与修改建议；需要修改或核实时必填。"/></label><button className="primary-button" type="submit" disabled={saving}>{saving?'正在保存…':'保存审核结论'}</button></form>
    <section className="review-history"><h2>审核历史</h2><p>最近 50 条记录，按时间倒序排列。</p>{detail.history.length?detail.history.map(h=><article key={h.version}><strong>{labels[h.status]}</strong><span>{h.reviewerName} · {new Date(h.createdAt).toLocaleString('zh-CN')} · 第 {h.version} 次</span>{h.contentHash!==detail.contentHash&&<small>针对旧版内容</small>}<p>{h.comment||'未附加意见'}</p></article>):<p>尚无审核记录。</p>}</section>
   </>:!error&&<p className="section-placeholder">{selected?'正在读取制度…':'请选择一项制度'}</p>}</section>
  </div>
 </div>;
}
