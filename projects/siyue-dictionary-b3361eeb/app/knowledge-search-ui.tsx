import type { EnterpriseSearchResult as KnowledgeSearchResult } from "../lib/enterprise-types";


export type KnowledgeView = "dictionary" | "processes" | "policies" | "needs" | "stats" | "members" | "library" | "organization" | "policy-review";
export type RoleSection = "guidance" | "requirements" | undefined;
export type KnowledgeRouteOptions = { role?: string; process?: string; policy?: string; document?: string; q?: string; section?: RoleSection };
type KnowledgeCatalog = { roleIds: string[]; processIds: string[]; policyIds: string[]; defaultRoleId: string };

export function readKnowledgeLocation(search: string, catalog: KnowledgeCatalog): { view: KnowledgeView; query: string; role?: string; process?: string; policy?: string; document?: string; section?: RoleSection } {
  const params = new URLSearchParams(search);
  const role = params.get("role") === "principal" ? "manager" : params.get("role");
  const process = params.get("process");
  const policy = params.get("policy");
  const document = params.get("document");
  const section = params.get("section");
  const requestedView = params.get("view");
  const query = (params.get("q") ?? "").slice(0, 200);
  const roleSection: RoleSection = section === "guidance" || section === "requirements" ? section : undefined;
  if (document && /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(document)) return { view: "library", query, document };
  if (policy && catalog.policyIds.includes(policy)) return { view: "policies" as const, query, policy };
  if (process && catalog.processIds.includes(process)) return { view: "processes" as const, query, process };
  if (role && catalog.roleIds.includes(role)) return { view: "dictionary" as const, query, role, section: roleSection };
  if (requestedView === "policy-review" || requestedView === "members" || requestedView === "needs" || requestedView === "stats" || requestedView === "policies" || requestedView === "library" || requestedView === "organization") return { view: requestedView, query };
  return { view: "dictionary" as const, query, role: catalog.defaultRoleId };
}

export function knowledgeLocation(href: string, view: KnowledgeView, options: KnowledgeRouteOptions) {
  const url = new URL(href);
  ["role", "process", "policy", "document", "view", "q", "section"].forEach(key => url.searchParams.delete(key));
  if (view === "dictionary" && options.role) url.searchParams.set("role", options.role);
  else if (view === "processes" && options.process) url.searchParams.set("process", options.process);
  else if (view === "policies" && options.policy) url.searchParams.set("policy", options.policy);
  else if (view === "library" && options.document) url.searchParams.set("document", options.document);
  else if (view !== "dictionary") url.searchParams.set("view", view);
  if (options.q?.trim()) url.searchParams.set("q", options.q.slice(0, 200));
  if (view === "dictionary" && options.section) url.searchParams.set("section", options.section);
  return url;
}

const resultTypes = { role: "岗位", policy: "制度", process: "流程", document: "工作资料" } as const;

export function KnowledgeSearchResults({ results, onSelect }: {
  results: KnowledgeSearchResult[];
  onSelect: (result: KnowledgeSearchResult) => void;
}) {
  return <section className="knowledge-results" aria-label="搜索结果">
    <p className="knowledge-result-count" role="status">{results.length ? `${results.length} 条结果` : "没有找到相关内容"}</p>
    {results.length > 0 && <ol>{results.map(result => <li key={result.key}>
      <button className="knowledge-result" onClick={() => onSelect(result)}>
        <span className="knowledge-result-heading"><strong>{result.title}</strong><small>{resultTypes[result.type]}{result.matchKind === "semantic" ? " · 相关表达" : ""}{result.section === "guidance" ? " · 完善建议" : ""}</small></span>
        {result.subtitle && <span className="knowledge-result-subtitle">{result.subtitle}</span>}
        {result.excerpt && <span className="knowledge-result-excerpt">{result.excerpt}</span>}
      </button>
    </li>)}</ol>}
  </section>;
}
