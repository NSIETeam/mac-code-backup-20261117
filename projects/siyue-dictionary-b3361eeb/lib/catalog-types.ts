import type { Candidate, ProcessEntry, RoleEntry, SourceRef } from './dictionary-types';
import type { PolicyDocument, PolicyEntry } from './policy-types';
import type { RoleGuidance } from './role-guidance';

/** Reference material is returned only by the authenticated catalog API. */
export type ReferenceCatalog = {
  roles: RoleEntry[];
  processes: ProcessEntry[];
  sources: SourceRef[];
  candidates: Candidate[];
  policies: PolicyEntry[];
  policyDocuments: PolicyDocument[];
  policySources: SourceRef[];
  roleGuidance: Readonly<Record<string, RoleGuidance>>;
  managementRoleIds: string[];
};

/** The registration form needs names, not internal duties or source excerpts. */
export type RegistrationRole = Pick<RoleEntry, 'id' | 'name' | 'group' | 'aliases'>;
