import type { KnowledgeAttachment, KnowledgeMember, KnowledgeSharing, OrgKind, OrgUnit } from '../lib/enterprise-types';

export const orgKindLabels: Record<OrgKind, string> = { department: '部门', campus: '校区', grade: '年级', project: '项目组' };
export const privateSharing = (): KnowledgeSharing => ({ mode: 'private', userIds: [], unitIds: [], match: 'any' });
export const toggleId = (ids: string[], id: string) => ids.includes(id) ? ids.filter(value => value !== id) : [...ids, id];
export const displayDate = (value: string) => new Date(value).toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
export function fileSize(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.ceil(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
export function sharingSummary(sharing: KnowledgeSharing, units: OrgUnit[], members: KnowledgeMember[]) {
  if (sharing.mode === 'private') return '仅自己';
  if (sharing.mode === 'employees') return '全体已确认员工';
  const namedUnits = sharing.unitIds.map(id => units.find(unit => unit.id === id)?.name ?? '指定组织');
  const namedPeople = sharing.userIds.map(id => members.find(member => member.id === id)?.name ?? '指定人员');
  const unitPart = namedUnits.length ? sharing.match === 'all' && namedUnits.length > 1 ? `同时属于「${namedUnits.join('」和「')}」的员工` : `「${namedUnits.join('」「')}」${namedUnits.length > 1 ? '任一范围内' : '内'}的员工` : '';
  return [unitPart, namedPeople.length ? `指定人员：${namedPeople.join('、')}` : ''].filter(Boolean).join('，以及') || '尚未选择共享对象';
}
export function validateSharing(sharing: KnowledgeSharing, active: boolean) {
  if (!active && sharing.mode !== 'private') return '身份确认后即可共享资料。';
  if (sharing.mode === 'selected' && !sharing.unitIds.length && !sharing.userIds.length) return '请选择至少一个共享对象。';
  return '';
}
export function cleanSharing(sharing: KnowledgeSharing): KnowledgeSharing {
  return sharing.mode === 'selected' ? { ...sharing, userIds: [...new Set(sharing.userIds)], unitIds: [...new Set(sharing.unitIds)] } : { ...sharing, userIds: [], unitIds: [], match: 'any' };
}
export function validateKnowledgeFile(file: Pick<File, 'name' | 'size'>) {
  if (!/\.(pdf|docx|xlsx|txt|md|png|jpe?g|webp)$/iu.test(file.name)) return '支持 PDF、Word、Excel、文本和 PNG / JPG / WebP 图片。';
  if (!file.size) return '文件为空，请选择有内容的文件。';
  if (file.size > 20 * 1024 * 1024) return '单个文件不能超过 20 MB。';
  return '';
}
export function attachmentLabel(attachment: KnowledgeAttachment) { return `${attachment.name} · ${fileSize(attachment.size)}`; }
