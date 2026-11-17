import { candidates, processes, roles, sources } from './dictionary-data';
import { policies, policyDocuments, policySources } from './policy-data';
import { isManagementGuidance, roleGuidance } from './role-guidance';
import { searchHybridKnowledge } from './knowledge-search';
import type { ReferenceCatalog } from './catalog-types';

// This entry is bundled into standalone/runtime, never the public web root.
// Callers must authenticate and verify active employment before returning it.
const catalog: ReferenceCatalog = {
  roles, processes, sources, candidates, policies, policyDocuments, policySources,
  roleGuidance, managementRoleIds: roles.filter(role => isManagementGuidance(role.id)).map(role => role.id),
};

export function getCatalog(): ReferenceCatalog { return catalog; }
export function searchCatalog(query: string, semantic = true) { return searchHybridKnowledge(query.slice(0, 200), semantic); }
