import type { SourceRef } from './dictionary-types';

export type PolicyDocument = {
  id: string;
  title: string;
  file: string;
  kind: '制度与规范' | '业务手册' | '培训参考';
  versionLabel: string;
  note: string;
};
export type PolicyEntry = {
  id: string;
  title: string;
  category: string;
  documentId: string;
  summary: string;
  keywords: string[];
  audience: string[];
  roleIds: string[];
  status: '书面规定 · 现行版本待确认' | '初稿 · 待确认' | '历史参考';
  sections: { title: string; paragraphs: string[]; refs: string[] }[];
  questions: string[];
};
export type PolicyBundle = { documents: PolicyDocument[]; entries: PolicyEntry[]; sources: SourceRef[] };
