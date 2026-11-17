"use client";

import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Check, LoaderCircle, Plus, Search } from 'lucide-react';
import type { EmployeeAccess, OrganizationEmployee, OrgKind, OrgUnit } from '../lib/enterprise-types';
import { appFetch } from './app-paths.mjs';
import { orgKindLabels, toggleId } from './enterprise-data';
import './enterprise.css';

type Callback = () => void | Promise<void>;
export type OrganizationAdminProps = { employeeId: string; onUnauthorized: Callback; onChanged?: Callback; roleNames?: Record<string, string> };
const statusLabels = { pending: '待确认', active: '已确认', suspended: '已停用' };
const errorText = (reason: unknown, fallback: string) => reason instanceof TypeError ? '暂时无法连接，请重试。' : reason instanceof Error ? reason.message : fallback;

export function OrganizationAdmin(props: OrganizationAdminProps) { return <OrganizationSession key={props.employeeId} {...props}/>; }

function OrganizationSession({ employeeId, onUnauthorized, onChanged, roleNames = {} }: OrganizationAdminProps) {
  const [employees, setEmployees] = useState<OrganizationEmployee[]>([]);
  const [units, setUnits] = useState<OrgUnit[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<'all' | EmployeeAccess['status']>('pending');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [revision, setRevision] = useState(0);
  const [creatingUnit, setCreatingUnit] = useState(false);
  const [unitName, setUnitName] = useState('');
  const [unitKind, setUnitKind] = useState<OrgKind>('department');
  const [unitError, setUnitError] = useState('');
  const [unitBusy, setUnitBusy] = useState(false);
  const mounted = useRef(true);
  const requests = useRef(new Set<AbortController>());
  const callbacks = useRef({ onUnauthorized, onChanged }); callbacks.current = { onUnauthorized, onChanged };
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; requests.current.forEach(request => request.abort()); }; }, []);

  const denied = useCallback(() => { if (mounted.current) { setEmployees([]); setUnits([]); setSelectedId(''); void callbacks.current.onUnauthorized(); } }, []);
  useEffect(() => {
    const controller = new AbortController(); requests.current.add(controller);
    setLoading(true); setError('');
    void (async () => {
      try {
        const response = await appFetch('/api/organization/employees', { cache: 'no-store', signal: controller.signal });
        const data = await response.json() as { employees?: OrganizationEmployee[]; units?: OrgUnit[]; error?: string };
        if (!mounted.current || controller.signal.aborted) return;
        if (response.status === 401 || response.status === 403) denied();
        if (!response.ok || !Array.isArray(data.employees) || !Array.isArray(data.units)) throw new Error(data.error ?? '人员权限暂时无法读取。');
        setEmployees(data.employees); setUnits(data.units);
      } catch (reason) { if (mounted.current && !controller.signal.aborted) { setEmployees([]); setUnits([]); setError(errorText(reason, '读取失败。')); } }
      finally { requests.current.delete(controller); if (mounted.current && !controller.signal.aborted) setLoading(false); }
    })();
    return () => { controller.abort(); requests.current.delete(controller); };
  }, [revision, denied]);

  const filtered = employees.filter(employee => {
    if (status !== 'all' && employee.access.status !== status) return false;
    const term = query.trim().toLocaleLowerCase();
    return !term || [employee.name, employee.department, employee.campus, employee.grade, ...employee.access.memberships.map(unit => unit.name), ...employee.roleIds.map(id => roleNames[id] ?? '')].join(' ').toLocaleLowerCase().includes(term);
  });
  const selected = employees.find(employee => employee.id === selectedId) ?? filtered[0];
  async function createUnit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (unitBusy || !unitName.trim()) return;
    const controller = new AbortController(); requests.current.add(controller); setUnitBusy(true); setUnitError('');
    try {
      const response = await appFetch('/api/organization/units', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: unitName.trim(), kind: unitKind }), signal: controller.signal });
      const data = await response.json() as { unit?: OrgUnit; error?: string };
      if (!mounted.current || controller.signal.aborted) return;
      if (response.status === 401 || response.status === 403) { denied(); return; }
      if (!response.ok || !data.unit) throw new Error(data.error ?? '组织范围未保存。');
      setUnits(previous => [...previous.filter(unit => unit.id !== data.unit!.id), data.unit!]); setUnitName(''); setCreatingUnit(false); setMessage(`已添加${orgKindLabels[data.unit.kind]}：${data.unit.name}`);
    } catch (reason) { if (mounted.current && !controller.signal.aborted) setUnitError(errorText(reason, '添加失败。')); }
    finally { requests.current.delete(controller); if (mounted.current) setUnitBusy(false); }
  }

  return <section className="organization-admin" aria-label="员工身份与权限">
    <div className="enterprise-toolbar"><nav className="enterprise-tabs" aria-label="员工状态">{(['pending', 'active', 'suspended', 'all'] as const).map(value => <button type="button" key={value} className={status === value ? 'selected' : ''} onClick={() => { setStatus(value); setSelectedId(''); setMessage(''); }}>{value === 'all' ? '全部' : statusLabels[value]}{value === 'pending' && employees.some(employee => employee.access.status === 'pending') && <small>{employees.filter(employee => employee.access.status === 'pending').length}</small>}</button>)}</nav><button type="button" className="text-button" disabled={loading} onClick={() => { setRevision(value => value + 1); setMessage(''); }}>刷新</button></div>
    {message && <p className="enterprise-feedback" role="status">{message}</p>}
    {error && <p className="form-error" role="alert">{error}</p>}
    <div className="organization-columns">
      <aside className="organization-index"><label className="global-search"><Search size={15}/><input type="search" aria-label="查找员工" placeholder="查找姓名、部门或校区" value={query} onChange={event => { setQuery(event.target.value); setSelectedId(''); }}/></label>{loading && !employees.length ? <p className="loading-state">正在读取…</p> : <div className="enterprise-list">{filtered.map(employee => <button type="button" key={employee.id} className={`enterprise-list-item${selected?.id === employee.id ? ' current' : ''}`} onClick={() => { setSelectedId(employee.id); setMessage(''); }}><span>{employee.name}{employee.id === employeeId && <small>我</small>}</span><small>{statusLabels[employee.access.status]}{employee.access.memberships.length ? ` · ${employee.access.memberships.map(unit => unit.name).join(' / ')}` : ''}</small></button>)}{!filtered.length && !loading && <p className="section-placeholder">{status === 'pending' && !query ? '暂无待确认员工。' : '没有匹配的员工。'}</p>}</div>}</aside>
      <div className="organization-reader">{selected ? <EmployeePermissionEditor key={`${selected.id}:${selected.access.version}`} employee={selected} units={units} selfId={employeeId} roleNames={roleNames} onUnauthorized={denied} onSaved={employee => { if (!mounted.current) return; setEmployees(previous => previous.map(item => item.id === employee.id ? employee : item)); setSelectedId(employee.id); setMessage(`${employee.name}的身份与权限已保存。`); void callbacks.current.onChanged?.(); }} onRefresh={() => setRevision(value => value + 1)}/> : !loading && <p className="section-placeholder">选择员工，确认其部门、校区与项目范围。</p>}
        <div className="organization-units"><button type="button" className="text-button" onClick={() => { setCreatingUnit(value => !value); setUnitError(''); }}><Plus size={14}/>{creatingUnit ? '收起' : '新增部门、校区或项目'}</button>{creatingUnit && <form className="organization-unit-form" onSubmit={createUnit}><label className="field"><span>类型</span><select value={unitKind} disabled={unitBusy} onChange={event => setUnitKind(event.target.value as OrgKind)}>{(Object.keys(orgKindLabels) as OrgKind[]).map(kind => <option key={kind} value={kind}>{orgKindLabels[kind]}</option>)}</select></label><label className="field"><span>名称</span><input value={unitName} maxLength={60} required disabled={unitBusy} onChange={event => setUnitName(event.target.value)} placeholder="填写实际组织名称"/></label><button type="submit" className="secondary-button" disabled={unitBusy || !unitName.trim()}>{unitBusy ? '正在添加…' : '添加'}</button>{unitError && <p className="form-error" role="alert">{unitError}</p>}</form>}</div>
      </div>
    </div>
  </section>;
}

function EmployeePermissionEditor({ employee, units, selfId, roleNames, onUnauthorized, onSaved, onRefresh }: { employee: OrganizationEmployee; units: OrgUnit[]; selfId: string; roleNames: Record<string, string>; onUnauthorized: Callback; onSaved: (employee: OrganizationEmployee) => void; onRefresh: () => void }) {
  const [status, setStatus] = useState(employee.access.status);
  const [unitIds, setUnitIds] = useState(employee.access.memberships.map(unit => unit.id));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [conflict, setConflict] = useState(false);
  const mounted = useRef(true);
  const activeSave = useRef(false);
  const controllerRef = useRef<AbortController | null>(null);
  const callbacks = useRef({ onUnauthorized, onSaved }); callbacks.current = { onUnauthorized, onSaved };
  const originalIds = employee.access.memberships.map(unit => unit.id).sort().join(',');
  const changed = status !== employee.access.status || [...unitIds].sort().join(',') !== originalIds;
  const roleLabels = employee.roleIds.flatMap(id => roleNames[id] ? [roleNames[id]] : []);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; controllerRef.current?.abort(); }; }, []);
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (activeSave.current) return;
    const controller = new AbortController(); controllerRef.current = controller;
    activeSave.current = true; setSaving(true); setError(''); setConflict(false);
    try {
      const response = await appFetch(`/api/organization/employees/${encodeURIComponent(employee.id)}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status, unitIds, version: employee.access.version }), signal: controller.signal });
      const data = await response.json() as { employee?: OrganizationEmployee; error?: string };
      if (!mounted.current || controller.signal.aborted) return;
      if (response.status === 401 || response.status === 403) { void callbacks.current.onUnauthorized(); return; }
      if (response.status === 409) setConflict(true);
      if (!response.ok || !data.employee) throw new Error(data.error ?? '权限没有保存，请重试。');
      callbacks.current.onSaved(data.employee);
    } catch (reason) { if (mounted.current && !controller.signal.aborted) setError(errorText(reason, '权限没有保存，请重试。')); }
    finally { activeSave.current = false; controllerRef.current = null; if (mounted.current) setSaving(false); }
  }
  return <form className="organization-permission-editor" onSubmit={save}>
    <div className="enterprise-document-heading"><h1>{employee.name}</h1>{employee.access.canManageKnowledge && <span className="enterprise-meta">身份与权限管理员</span>}</div>
    <div className="organization-requested"><span>本人填写</span><p>{[roleLabels.join(' / '), employee.department, employee.campus, employee.grade].filter(Boolean).join(' · ') || '暂无补充资料'}</p></div>
    <label className="field organization-status-select"><span>身份状态</span><select value={status} disabled={saving || employee.id === selfId || employee.access.canManageKnowledge} onChange={event => setStatus(event.target.value as EmployeeAccess['status'])}><option value="pending">待确认</option><option value="active">已确认员工</option><option value="suspended">停用</option></select></label>
    <p className="organization-scope-label">确认的组织范围</p>
    {(Object.keys(orgKindLabels) as OrgKind[]).map(kind => { const matching = units.filter(unit => unit.kind === kind); return matching.length ? <fieldset key={kind} className="enterprise-choice-fieldset"><legend>{orgKindLabels[kind]}</legend><div className="enterprise-checkboxes">{matching.map(unit => <label key={unit.id}><input type="checkbox" checked={unitIds.includes(unit.id)} disabled={saving} onChange={() => setUnitIds(previous => toggleId(previous, unit.id))}/><span>{unit.name}</span></label>)}</div></fieldset> : null; })}
    <p className="enterprise-sharing-preview">{status === 'active' ? unitIds.length ? `已选：${unitIds.map(id => units.find(unit => unit.id === id)?.name ?? '指定组织').join('、')}` : '未选组织范围：可查看全员资料及明确分享给本人的资料。' : status === 'pending' ? '待确认期间仅可保存个人资料。' : '停用后不能访问组织知识。'}</p>
    {error && <p className="form-error" role="alert">{error}</p>}
    {conflict && <button type="button" className="text-button" onClick={onRefresh}>重新载入最新权限</button>}
    <div className="organization-save-row"><button type="submit" className="primary-button" disabled={saving || !changed || conflict}>{saving ? <><LoaderCircle size={15} className="spin"/>正在保存…</> : <><Check size={15}/>{employee.access.status === 'pending' && status === 'active' ? '确认员工与权限' : '保存权限'}</>}</button></div>
  </form>;
}
