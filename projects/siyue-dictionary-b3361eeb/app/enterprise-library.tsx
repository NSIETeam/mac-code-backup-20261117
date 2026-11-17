"use client";

import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { ArrowDownToLine, Check, FileText, LoaderCircle, Pencil, Plus, Upload, X } from 'lucide-react';
import type { EmployeeAccess, KnowledgeAttachment, KnowledgeContext, KnowledgeSharing, WorkDocument, WorkDocumentInput } from '../lib/enterprise-types';
import { appFetch } from './app-paths.mjs';
import { attachmentLabel, cleanSharing, displayDate, orgKindLabels, privateSharing, sharingSummary, toggleId, validateKnowledgeFile, validateSharing } from './enterprise-data';
import './enterprise.css';

type Callback = () => void | Promise<void>;
export type EnterpriseLibraryProps = { employeeId: string; access: EmployeeAccess; selectedId?: string; onSelect: (id: string) => void; onUnauthorized: Callback; onChanged?: Callback };
type FileEntry = { key: string; file?: File; attachment?: KnowledgeAttachment; uploading: boolean; error: string };
const fallbackError = (reason: unknown, text: string) => reason instanceof TypeError ? '暂时无法连接，请重试。' : reason instanceof Error ? reason.message : text;

export function EnterpriseLibrary(props: EnterpriseLibraryProps) {
  return <LibrarySession key={`${props.employeeId}:${props.access.version}:${props.access.status}`} {...props}/>;
}

function LibrarySession({ employeeId, access, selectedId, onSelect, onUnauthorized, onChanged }: EnterpriseLibraryProps) {
  const [context, setContext] = useState<KnowledgeContext | null>(null);
  const [items, setItems] = useState<WorkDocument[]>([]);
  const [scope, setScope] = useState<'all' | 'mine'>('all');
  const [selection, setSelection] = useState(selectedId ?? '');
  const [document, setDocument] = useState<WorkDocument | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState('');
  const [detailError, setDetailError] = useState('');
  const [revision, setRevision] = useState(0);
  const [editor, setEditor] = useState<WorkDocument | 'new' | null>(null);
  const [downloading, setDownloading] = useState('');
  const [message, setMessage] = useState('');
  const mounted = useRef(true);
  const actions = useRef({ onUnauthorized, onChanged }); actions.current = { onUnauthorized, onChanged };
  const editing = useRef(false); editing.current = !!editor;
  const requests = useRef(new Set<AbortController>());
  const urls = useRef(new Set<string>());
  const chosenId = selectedId || selection || items[0]?.id || '';
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; requests.current.forEach(request => request.abort()); urls.current.forEach(url => URL.revokeObjectURL(url)); }; }, []);
  useEffect(() => { if (selectedId) setSelection(selectedId); }, [selectedId]);
  useEffect(() => {
    const refresh = () => { if (mounted.current && !editing.current && window.document.visibilityState === 'visible') setRevision(value => value + 1); };
    const timer = window.setInterval(refresh, 30_000);
    window.addEventListener('focus', refresh); window.document.addEventListener('visibilitychange', refresh);
    return () => { window.clearInterval(timer); window.removeEventListener('focus', refresh); window.document.removeEventListener('visibilitychange', refresh); };
  }, []);

  useEffect(() => {
    const controller = new AbortController(); requests.current.add(controller);
    setLoading(true); setError('');
    void (async () => {
      try {
        const responses = await Promise.all([appFetch('/api/knowledge/context', { cache: 'no-store', signal: controller.signal }), appFetch(`/api/knowledge?scope=${scope}`, { cache: 'no-store', signal: controller.signal })]);
        const [nextContext, documents] = await Promise.all(responses.map(response => response.json())) as [KnowledgeContext & { error?: string }, { items?: WorkDocument[]; error?: string }];
        if (!mounted.current || controller.signal.aborted) return;
        if (responses.some(response => response.status === 401 || response.status === 403)) {
          setItems([]); setDocument(null); setContext(null); setEditor(null); void actions.current.onUnauthorized();
        }
        if (!responses[0].ok || !responses[1].ok || !Array.isArray(documents.items)) throw new Error(nextContext.error ?? documents.error ?? '资料暂时无法读取。');
        if (nextContext.access.version !== access.version || nextContext.access.status !== access.status || nextContext.access.canManageKnowledge !== access.canManageKnowledge) {
          setItems([]); setDocument(null); setContext(null); void actions.current.onUnauthorized(); return;
        }
        setContext(nextContext); setItems(documents.items);
      } catch (reason) { if (mounted.current && !controller.signal.aborted) { setItems([]); setDocument(null); setError(fallbackError(reason, '资料读取失败。')); } }
      finally { requests.current.delete(controller); if (mounted.current && !controller.signal.aborted) setLoading(false); }
    })();
    return () => { controller.abort(); requests.current.delete(controller); };
  }, [scope, revision]);

  useEffect(() => {
    setDocument(null); setDetailError('');
    if (!chosenId) { setDetailLoading(false); return; }
    const controller = new AbortController(); requests.current.add(controller); setDetailLoading(true);
    void (async () => {
      try {
        const response = await appFetch(`/api/knowledge/${encodeURIComponent(chosenId)}`, { cache: 'no-store', signal: controller.signal });
        const data = await response.json() as { document?: WorkDocument; error?: string };
        if (!mounted.current || controller.signal.aborted) return;
        if (response.status === 401) void actions.current.onUnauthorized();
        if (!response.ok || !data.document) {
          if (response.status === 403 || response.status === 404) setItems(previous => previous.filter(item => item.id !== chosenId));
          throw new Error(data.error ?? '资料不存在或已无权查看。');
        }
        setDocument(data.document);
      } catch (reason) { if (mounted.current && !controller.signal.aborted) { setDocument(null); setDetailError(fallbackError(reason, '资料读取失败。')); } }
      finally { requests.current.delete(controller); if (mounted.current && !controller.signal.aborted) setDetailLoading(false); }
    })();
    return () => { controller.abort(); requests.current.delete(controller); };
  }, [chosenId, revision]);

  async function download(attachment: KnowledgeAttachment) {
    if (downloading) return;
    const controller = new AbortController(); requests.current.add(controller); setDownloading(attachment.id); setDetailError('');
    try {
      const response = await appFetch(`/api/knowledge/attachments/${encodeURIComponent(attachment.id)}`, { cache: 'no-store', signal: controller.signal });
      if (!response.ok) {
        if (mounted.current && (response.status === 401 || response.status === 403 || response.status === 404)) {
          setDocument(null); setItems(previous => previous.filter(item => item.id !== chosenId)); setRevision(value => value + 1);
          if (response.status === 401) void actions.current.onUnauthorized();
        }
        const data = await response.json().catch(() => ({})) as { error?: string };
        throw new Error(data.error ?? '附件不存在或已无权下载。');
      }
      const blob = await response.blob();
      if (!mounted.current || controller.signal.aborted) return;
      const url = URL.createObjectURL(blob); urls.current.add(url);
      const anchor = window.document.createElement('a'); anchor.href = url; anchor.download = attachment.name; anchor.click();
      window.setTimeout(() => { URL.revokeObjectURL(url); urls.current.delete(url); }, 30_000);
    } catch (reason) { if (mounted.current && !controller.signal.aborted) setDetailError(fallbackError(reason, '下载失败。')); }
    finally { requests.current.delete(controller); if (mounted.current) setDownloading(''); }
  }

  function changeScope(next: 'all' | 'mine') {
    if (next === scope) return;
    setScope(next); setSelection(''); setItems([]); setDocument(null); setDetailError(''); setMessage(''); onSelect('');
  }

  if (access.status === 'suspended') return <p className="enterprise-status">账号已停用，请联系管理员。</p>;
  return <section className="enterprise-library" aria-label="工作资料">
    <div className="enterprise-toolbar"><nav className="enterprise-tabs" aria-label="工作资料范围"><button type="button" className={scope === 'all' ? 'selected' : ''} onClick={() => changeScope('all')}>全部可见</button><button type="button" className={scope === 'mine' ? 'selected' : ''} onClick={() => changeScope('mine')}>我的资料</button></nav><div className="enterprise-actions"><button type="button" className="text-button" disabled={loading} onClick={() => setRevision(value => value + 1)}>刷新</button><button type="button" className="primary-button" disabled={!context} onClick={() => { setEditor('new'); setMessage(''); }}><Plus size={14}/>上传资料</button></div></div>
    {access.status === 'pending' && <p className="enterprise-status">身份待确认。你可以先保存个人资料，确认后即可查看组织知识并共享。</p>}
    {message && <p className="enterprise-feedback" role="status">{message}</p>}
    {error && <p className="form-error" role="alert">{error}</p>}
    {loading && !items.length ? <p className="loading-state">正在读取…</p> : <div className="enterprise-columns">
      <aside className="enterprise-index" aria-label="资料目录">{items.length ? <div className="enterprise-list">{items.map(item => <button type="button" key={item.id} className={`enterprise-list-item${chosenId === item.id ? ' current' : ''}`} onClick={() => { setSelection(item.id); onSelect(item.id); setMessage(''); }}><span>{item.title}</span><small>{item.author} · {new Date(item.updatedAt).toLocaleDateString('zh-CN')}</small></button>)}</div> : <p className="section-placeholder">{scope === 'mine' ? '还没有上传资料。' : '暂无可见资料。'}</p>}</aside>
      <div className="enterprise-reader">{detailLoading ? <p className="loading-state">正在读取…</p> : document ? <article>
        <div className="enterprise-document-heading"><h1>{document.title}</h1>{document.canEdit && <button type="button" className="text-button" onClick={() => { setEditor(document); setMessage(''); }}><Pencil size={14}/>编辑</button>}</div>
        <p className="enterprise-meta">工作资料 · {document.author} · {displayDate(document.updatedAt)}</p>
        {context && <p className="enterprise-meta">可见：{sharingSummary(document.sharing, context.units, context.members)}</p>}
        {document.body && <div className="enterprise-body">{document.body}</div>}
        {document.attachments.length > 0 && <ul className="enterprise-attachments" aria-label="附件">{document.attachments.map(attachment => <li key={attachment.id}><button type="button" onClick={() => download(attachment)} disabled={!!downloading}><FileText size={16}/><span>{attachmentLabel(attachment)}</span>{downloading === attachment.id ? <LoaderCircle size={15} className="spin"/> : <ArrowDownToLine size={15}/>}</button></li>)}</ul>}
      </article> : !detailError && <p className="section-placeholder">{items.length ? '选择一份资料。' : '工作经验、模板和记录，都可以保存在这里。'}</p>}
      {detailError && <p className="form-error" role="alert">{detailError}</p>}</div>
    </div>}
    {editor && context && <KnowledgeEditor key={editor === 'new' ? 'new' : editor.id} employeeId={employeeId} context={context} initial={editor === 'new' ? undefined : editor} onClose={() => { setEditor(null); setRevision(value => value + 1); }} onUnauthorized={() => { setEditor(null); setDocument(null); setItems([]); return actions.current.onUnauthorized(); }} onSaved={saved => { if (!mounted.current) return; setEditor(null); setSelection(saved.id); setDocument(saved); setScope('all'); onSelect(saved.id); setRevision(value => value + 1); setMessage(saved.sharing.mode === 'private' ? '已保存，仅自己可见。' : '已按所选范围共享。'); void actions.current.onChanged?.(); }}/>} 
  </section>;
}

function EnterpriseDialog({ title, children, busy, onClose }: { title: string; children: ReactNode; busy: boolean; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => { const dialog = ref.current; dialog?.showModal(); return () => { if (dialog?.open) dialog.close(); }; }, []);
  return <dialog className="app-dialog enterprise-dialog" ref={ref} aria-label={title} onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}><div className="dialog-surface"><header className="dialog-header"><h2>{title}</h2><button type="button" className="icon-button" aria-label="关闭" disabled={busy} onClick={onClose}><X size={19}/></button></header>{children}</div></dialog>;
}

function KnowledgeEditor({ context, initial, onClose, onSaved, onUnauthorized }: { employeeId: string; context: KnowledgeContext; initial?: WorkDocument; onClose: () => void; onSaved: (document: WorkDocument) => void; onUnauthorized: Callback }) {
  const [title, setTitle] = useState(initial?.title ?? '');
  const [body, setBody] = useState(initial?.body ?? '');
  const [sharing, setSharing] = useState<KnowledgeSharing>(initial ? { ...initial.sharing, unitIds: [...initial.sharing.unitIds], userIds: [...initial.sharing.userIds] } : privateSharing());
  const [files, setFiles] = useState<FileEntry[]>((initial?.attachments ?? []).map(attachment => ({ key: attachment.id, attachment, uploading: false, error: '' })));
  const [saving, setSaving] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [error, setError] = useState('');
  const [dragging, setDragging] = useState(false);
  const [peopleQuery, setPeopleQuery] = useState('');
  const fileInput = useRef<HTMLInputElement>(null);
  const mounted = useRef(true);
  const activeSave = useRef(false);
  const filesRef = useRef(files); filesRef.current = files;
  const controllers = useRef(new Map<string, AbortController>());
  const uploadQueue = useRef(Promise.resolve());
  const completedUploads = useRef(new Set<string>());
  const requestId = useRef(crypto.randomUUID());
  const attempt = useRef<WorkDocumentInput | null>(null);
  const callbacks = useRef({ onUnauthorized, onSaved }); callbacks.current = { onUnauthorized, onSaved };
  const active = context.access.status === 'active';
  const uploading = files.some(file => file.uploading);
  const frozen = saving || uncertain;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; controllers.current.forEach(controller => controller.abort()); }; }, []);

  const uploadFile = useCallback(async (entry: FileEntry) => {
    if (!mounted.current || !entry.file || controllers.current.has(entry.key) || completedUploads.current.has(entry.key) || !filesRef.current.some(file => file.key === entry.key)) return;
    const controller = new AbortController(); controllers.current.set(entry.key, controller);
    setFiles(previous => previous.map(item => item.key === entry.key ? { ...item, uploading: true, error: '' } : item));
    try {
      const response = await appFetch(`/api/knowledge/attachments?name=${encodeURIComponent(entry.file.name)}`, { method: 'POST', headers: { 'Content-Type': entry.file.type || 'application/octet-stream' }, body: entry.file, signal: controller.signal });
      const data = await response.json() as { attachment?: KnowledgeAttachment; error?: string };
      if (!mounted.current || controller.signal.aborted) return;
      if (response.status === 401 || response.status === 403) { void callbacks.current.onUnauthorized(); return; }
      if (!response.ok || !data.attachment) throw new Error(data.error ?? '附件上传失败。');
      completedUploads.current.add(entry.key);
      setFiles(previous => previous.map(item => item.key === entry.key ? { ...item, attachment: data.attachment, uploading: false, error: '' } : item));
    } catch (reason) { if (mounted.current && !controller.signal.aborted) setFiles(previous => previous.map(item => item.key === entry.key ? { ...item, uploading: false, error: fallbackError(reason, '附件上传失败。') } : item)); }
    finally { controllers.current.delete(entry.key); }
  }, []);
  function enqueueUpload(entry: FileEntry) {
    setFiles(previous => previous.map(item => item.key === entry.key ? { ...item, uploading: true, error: '' } : item));
    uploadQueue.current = uploadQueue.current.then(() => uploadFile(entry));
  }

  function addFiles(selected: FileList | File[]) {
    if (frozen) return;
    const candidates = Array.from(selected);
    if (filesRef.current.length + candidates.length > 5) { setError('每份资料最多添加 5 个附件。'); return; }
    const invalid = candidates.map(file => ({ file, reason: validateKnowledgeFile(file) })).find(item => item.reason);
    if (invalid) { setError(`${invalid.file.name}：${invalid.reason}`); return; }
    const entries = candidates.map(file => ({ key: crypto.randomUUID(), file, uploading: true, error: '' }));
    const next = [...filesRef.current, ...entries]; filesRef.current = next; setFiles(next); setError('');
    if (!title.trim() && candidates[0]) setTitle(candidates[0].name.replace(/\.[^.]+$/u, '').slice(0, 120));
    entries.forEach(enqueueUpload);
  }
  function removeFile(key: string) {
    controllers.current.get(key)?.abort(); controllers.current.delete(key);
    const next = filesRef.current.filter(item => item.key !== key); filesRef.current = next; setFiles(next);
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (activeSave.current || uploading || conflict) return;
    if (!uncertain) {
      const invalidScope = validateSharing(sharing, active);
      if (!title.trim()) { setError('请填写资料标题。'); return; }
      if (!body.trim() && !files.some(file => file.attachment)) { setError('请填写内容或添加一个附件。'); return; }
      if (files.some(file => file.error || !file.attachment)) { setError('请先重试或移除未上传完成的附件。'); return; }
      if (invalidScope) { setError(invalidScope); return; }
      attempt.current = { ...(initial ? { version: initial.version } : { requestId: requestId.current }), title: title.trim(), body: body.trim(), sharing: cleanSharing(sharing), attachmentIds: files.flatMap(file => file.attachment ? [file.attachment.id] : []) };
    }
    const controller = new AbortController(); controllers.current.set('save', controller);
    activeSave.current = true; setSaving(true); setError('');
    let receivedResponse = false;
    try {
      const response = await appFetch(initial ? `/api/knowledge/${encodeURIComponent(initial.id)}` : '/api/knowledge', { method: initial ? 'PATCH' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(attempt.current), signal: controller.signal });
      const data = await response.json() as { document?: WorkDocument; error?: string };
      receivedResponse = true;
      if (!mounted.current || controller.signal.aborted) return;
      if (response.status === 401 || response.status === 403) { void callbacks.current.onUnauthorized(); return; }
      if (!response.ok || !data.document) {
        // A retry after a lost PATCH response may find its version already saved.
        // Resolve that exact document before offering to overwrite a newer edit.
        if (initial && response.status === 409 && uncertain && attempt.current) {
          const check = await appFetch(`/api/knowledge/${encodeURIComponent(initial.id)}`, { cache: 'no-store', signal: controller.signal });
          const latest = await check.json() as { document?: WorkDocument };
          if (!mounted.current || controller.signal.aborted) return;
          const saved = latest.document, input = attempt.current;
          if (check.ok && saved && saved.title === input.title && saved.body === input.body && JSON.stringify(cleanSharing(saved.sharing)) === JSON.stringify(input.sharing) && saved.attachments.map(item => item.id).sort().join(',') === [...input.attachmentIds].sort().join(',')) { callbacks.current.onSaved(saved); return; }
        }
        setUncertain(false); if (response.status === 409) setConflict(true);
        throw new Error(data.error ?? '资料没有保存，请重试。');
      }
      callbacks.current.onSaved(data.document);
    } catch (reason) {
      if (mounted.current && !controller.signal.aborted) {
        if (!receivedResponse) { setUncertain(true); setError('保存结果暂未确认。内容已保留，点击重试确认，避免重复提交。'); }
        else setError(fallbackError(reason, '保存失败，内容已保留。'));
      }
    } finally { controllers.current.delete('save'); activeSave.current = false; if (mounted.current) setSaving(false); }
  }

  const matches = context.members.filter(member => !peopleQuery.trim() || `${member.name} ${member.memberships.map(unit => unit.name).join(' ')}`.toLocaleLowerCase().includes(peopleQuery.trim().toLocaleLowerCase()));
  return <EnterpriseDialog title={initial ? '编辑资料' : '上传资料'} busy={saving || uploading} onClose={onClose}><form onSubmit={submit}>
    <div className="form-body enterprise-editor-body">
      <label className="field"><span>标题</span><input name="knowledge-title" value={title} onChange={event => setTitle(event.target.value)} required maxLength={120} disabled={frozen} autoFocus placeholder="资料名称"/></label>
      <label className="field"><span>内容</span><textarea name="knowledge-body" value={body} onChange={event => setBody(event.target.value)} maxLength={12000} rows={6} disabled={frozen} placeholder="写下工作内容、经验或说明，也可以直接上传文件。"/></label>
      <div className={`enterprise-file-drop${dragging ? ' dragging' : ''}`} onDragOver={event => { event.preventDefault(); if (!frozen) setDragging(true); }} onDragLeave={() => setDragging(false)} onDrop={event => { event.preventDefault(); setDragging(false); addFiles(event.dataTransfer.files); }}>
        <input ref={fileInput} type="file" multiple accept=".pdf,.docx,.xlsx,.txt,.md,.png,.jpg,.jpeg,.webp" hidden disabled={frozen} onChange={event => { if (event.target.files) addFiles(event.target.files); event.target.value = ''; }}/>
        <button type="button" className="text-button" disabled={frozen || files.length >= 5} onClick={() => fileInput.current?.click()}><Upload size={15}/>添加文件</button><span>或拖到这里 · 最多 5 个，每个 20 MB</span>
      </div>
      {files.length > 0 && <ul className="enterprise-upload-list" aria-label="待保存附件">{files.map(file => <li key={file.key}><div><span>{file.attachment?.name ?? file.file?.name}</span>{file.uploading ? <small role="status">正在上传…</small> : file.error ? <small role="alert">{file.error}</small> : <small>已上传</small>}</div>{file.error && <button type="button" className="text-button" disabled={frozen} onClick={() => enqueueUpload(file)}>重试</button>}<button type="button" className="icon-button" aria-label={`移除 ${file.attachment?.name ?? file.file?.name}`} disabled={frozen} onClick={() => removeFile(file.key)}><X size={15}/></button></li>)}</ul>}
      <label className="field enterprise-sharing-select"><span>谁可以看</span><select value={sharing.mode} disabled={frozen || !active} onChange={event => setSharing(previous => ({ ...previous, mode: event.target.value as KnowledgeSharing['mode'] }))}><option value="private">仅自己</option><option value="employees">全体员工</option><option value="selected">指定人员或组织</option></select>{!active && <small>身份确认后可选择共享范围。</small>}</label>
      {sharing.mode === 'selected' && <div className="enterprise-sharing-options">
        {(Object.keys(orgKindLabels) as Array<keyof typeof orgKindLabels>).map(kind => { const units = context.units.filter(unit => unit.kind === kind); return units.length ? <fieldset key={kind} className="enterprise-choice-fieldset"><legend>{orgKindLabels[kind]}</legend><div className="enterprise-checkboxes">{units.map(unit => <label key={unit.id}><input type="checkbox" checked={sharing.unitIds.includes(unit.id)} disabled={frozen} onChange={() => setSharing(previous => ({ ...previous, unitIds: toggleId(previous.unitIds, unit.id) }))}/><span>{unit.name}</span></label>)}</div></fieldset> : null; })}
        {sharing.unitIds.length > 1 && <label className="field enterprise-match"><span>组织范围</span><select value={sharing.match} disabled={frozen} onChange={event => setSharing(previous => ({ ...previous, match: event.target.value as KnowledgeSharing['match'] }))}><option value="any">属于任一所选组织</option><option value="all">必须同时属于全部所选组织</option></select></label>}
        <fieldset className="enterprise-choice-fieldset"><legend>指定人员</legend><input type="search" className="enterprise-person-search" placeholder="查找姓名或部门" aria-label="查找共享人员" value={peopleQuery} disabled={frozen} onChange={event => setPeopleQuery(event.target.value)}/><div className="enterprise-checkboxes enterprise-people-choices">{matches.map(member => <label key={member.id}><input type="checkbox" checked={sharing.userIds.includes(member.id)} disabled={frozen} onChange={() => setSharing(previous => ({ ...previous, userIds: toggleId(previous.userIds, member.id) }))}/><span>{member.name}<small>{member.memberships.filter(unit => unit.kind === 'department').map(unit => unit.name).join(' / ')}</small></span></label>)}{!matches.length && <p className="enterprise-meta">没有匹配的员工。</p>}</div></fieldset>
      </div>}
      <p className="enterprise-sharing-preview" aria-live="polite">可见：{sharingSummary(sharing, context.units, context.members)}</p>
      {error && <p className="form-error" role="alert">{error}</p>}
      {conflict && <p className="enterprise-meta">资料已有更新。当前文字仍保留在这里，可复制后关闭窗口，再重新打开最新资料。</p>}
    </div>
    <footer className="form-footer"><button type="button" className="text-button" disabled={saving || uploading} onClick={onClose}>取消</button><button type="submit" className="primary-button" disabled={saving || uploading || conflict}>{saving ? <><LoaderCircle size={15} className="spin"/>正在保存…</> : uncertain ? '重试确认' : <><Check size={15}/>{initial ? '保存修改' : sharing.mode === 'private' ? '保存' : '共享'}</>}</button></footer>
  </form></EnterpriseDialog>;
}
