import { useEffect, useState } from "react";
import { ChevronDown } from "lucide-react";
import type { RoleGuidance, RoleGuidanceItem } from "../lib/role-guidance";

const guidanceText = (text: string) => text.replace(/^(?:建议待确认：|建议先确认：)/u, "");

function GuidanceItems({ title, items }: { title: string; items: RoleGuidanceItem[] }) {
  if (!items.length) return null;
  return <section className="guidance-group"><h2>{title}</h2><div className="guidance-items">{items.map((item, index) => <div key={`${item.title}-${index}`}><h3>{item.title}</h3><p>{guidanceText(item.text)}</p></div>)}</div></section>;
}

export function RoleGuidanceSection({ guidance, open = false }: { guidance: RoleGuidance; open?: boolean }) {
  const [expanded, setExpanded] = useState(open);
  useEffect(() => setExpanded(open), [open]);
  return <details className="role-guidance" open={expanded} onToggle={event => setExpanded(event.currentTarget.open)}>
    <summary>岗位完善建议<ChevronDown size={15}/></summary>
    <div className="role-guidance-body">
      <p className="guidance-notice">以下为建议，待集团确认；不代表现行制度。优先复用已有业务记录，不重复填表。</p>
      <p className="guidance-summary">{guidanceText(guidance.summary)}</p>
      <GuidanceItems title="职责要求" items={guidance.requirements}/>
      <GuidanceItems title="检查口径" items={guidance.checks}/>
      <GuidanceItems title="交接" items={guidance.handoffs}/>
      {guidance.questions.length > 0 && <section className="guidance-group"><h2>需确认的问题</h2><ol className="question-list">{guidance.questions.map((question, index) => <li key={index}>{guidanceText(question)}</li>)}</ol></section>}
    </div>
  </details>;
}
