export type OrgKind = 'department' | 'campus' | 'grade' | 'project';
export type OrgUnit = { id: string; kind: OrgKind; name: string };
export type EmployeeAccess = { status: 'pending' | 'active' | 'suspended'; version: number; memberships: OrgUnit[]; canManageKnowledge: boolean };
export type KnowledgeMember = { id: string; name: string; memberships: OrgUnit[] };
export type KnowledgeContext = { access: EmployeeAccess; units: OrgUnit[]; members: KnowledgeMember[] };
export type KnowledgeSharing = { mode: 'private' | 'employees' | 'selected'; userIds: string[]; unitIds: string[]; match: 'any' | 'all' };
export type KnowledgeAttachment = { id: string; name: string; size: number; mimeType: string };
export type WorkDocument = {
  id: string; title: string; body: string; ownerId: string; author: string; ownerUnits: OrgUnit[];
  sharing: KnowledgeSharing; attachments: KnowledgeAttachment[]; version: number;
  createdAt: string; updatedAt: string; canEdit: boolean;
};
export type WorkDocumentInput = { requestId?: string; title: string; body: string; sharing: KnowledgeSharing; attachmentIds: string[]; version?: number };
export type OrganizationEmployee = { id: string; name: string; roleIds: string[]; department: string; campus: string; grade: string; access: EmployeeAccess };
export type EnterpriseSearchResult = { key: string; type: 'role' | 'policy' | 'process' | 'document'; id: string; title: string; subtitle: string; excerpt: string; section?: 'guidance' | 'requirements'; matchKind?: 'semantic' };
