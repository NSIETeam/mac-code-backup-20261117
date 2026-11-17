import { ChevronDown, FileText } from "lucide-react";
import type { PolicyDocument, PolicyEntry } from "../lib/policy-types";

export function PolicyReader({ policy, document, roleNames, onSources, onRole }: {
  policy: PolicyEntry;
  document?: PolicyDocument;
  roleNames: Readonly<Record<string, string>>;
  onSources: (refs: string[]) => void;
  onRole: (id: string) => void;
}) {
  const relatedRoleIds = policy.audience.includes("全体员工") ? [] : policy.roleIds.filter(id => roleNames[id]);
  return <article className="dictionary-article policy-article">
    <div className="entry-heading"><h1>{policy.title}</h1></div>
    <p className="evidence-caption">{policy.status}</p>
    {document && <div className="policy-document-meta">
      <p>原文件：{document.file}</p>
      <details className="policy-document-notes"><summary>资料说明<ChevronDown size={13}/></summary><div>
        <p>版本：{document.versionLabel}</p>{document.note && <p>{document.note}</p>}
      </div></details>
    </div>}
    {policy.audience.length > 0 && <p className="policy-audience">适用对象：{policy.audience.join("、")}</p>}
    {policy.sections.map((section, index) => <section className="article-section policy-section" key={`${section.title}-${index}`}>
      <h2>{section.title}</h2>
      {section.paragraphs.map((paragraph, paragraphIndex) => <p key={paragraphIndex}>{paragraph}</p>)}
      {section.refs.length > 0 && <button className="source-link" onClick={() => onSources(section.refs)}><FileText size={12}/>资料依据</button>}
    </section>)}
    {policy.questions.length > 0 && <section className="article-section"><h2>待确认</h2><ol className="question-list">{policy.questions.map((question, index) => <li key={index}>{question}</li>)}</ol></section>}
    {relatedRoleIds.length > 0 && <section className="article-section"><h2>相关岗位</h2><div className="related-links">{relatedRoleIds.map(id => <button className="text-button" key={id} onClick={() => onRole(id)}>{roleNames[id]}</button>)}</div></section>}
  </article>;
}
