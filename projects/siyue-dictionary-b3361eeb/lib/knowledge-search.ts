import { processes, roles } from './dictionary-data';
import { policies, policyDocuments } from './policy-data';
import { roleGuidance } from './role-guidance';
import {prepareSemanticQuery, prepareSemanticEntry, semanticMatch, SEMANTIC_RESULT_LIMIT} from '../standalone/domain-search.mjs';

export type KnowledgeSearchResult = {
  key: string;
  type: 'role' | 'policy' | 'process';
  id: string;
  title: string;
  subtitle: string;
  excerpt: string;
  section?: 'guidance' | 'requirements';
  matchKind?: 'semantic';
  semanticScore?: number;
};
type Piece = { text: string; section?: KnowledgeSearchResult['section'] };
type IndexedEntry = Omit<KnowledgeSearchResult, 'excerpt' | 'section'> & {
  aliases: string[];
  summary: string;
  pieces: Piece[];
};
const documentMap = new Map(policyDocuments.map(document => [document.id, document]));
const normalized = (text: string) => text.normalize('NFKC').toLowerCase().replace(/\s+/gu, ' ').trim();
const piece = (text: string, section?: Piece['section']): Piece => ({ text, section });

// The index contains authored reference material only. Never add members,
// employee submissions, recording metadata, ratings, or account state here.
const entries: IndexedEntry[] = [
  ...roles.map(role => {
    const guidance = roleGuidance[role.id];
    return {
      key: `role:${role.id}`, type: 'role' as const, id: role.id, title: role.name,
      subtitle: role.group, aliases: role.aliases, summary: role.summary,
      pieces: [
        piece(role.summary), piece(role.scope, 'requirements'),
        ...role.duties.map(item => piece(`${item.title}：${item.text}`)),
        ...[...role.schedule, ...role.checks].map(item => piece(`${item.title}：${item.text}`, 'requirements')),
        ...role.handoffs.map(item => piece(`${item.from} → ${item.to}：${item.deliverable}。${item.acceptance}`, 'requirements')),
        ...role.questions.map(text => piece(text, 'requirements')),
        ...(guidance ? [
          piece(guidance.summary, 'guidance'),
          ...[...guidance.requirements, ...guidance.checks, ...guidance.handoffs].map(item => piece(`${item.title}：${item.text}`, 'guidance')),
          ...guidance.questions.map(text => piece(text, 'guidance')),
        ] : []),
      ],
    };
  }),
  ...policies.map(policy => ({
    key: `policy:${policy.id}`, type: 'policy' as const, id: policy.id, title: policy.title,
    subtitle: `${policy.category} · ${policy.status}`,
    aliases: [...policy.keywords, ...policy.audience, documentMap.get(policy.documentId)?.title ?? '', documentMap.get(policy.documentId)?.file ?? ''],
    summary: policy.summary,
    pieces: [piece(policy.summary), ...policy.sections.flatMap(section => [piece(section.title), ...section.paragraphs.map(text => piece(text))]), ...policy.questions.map(text => piece(text))],
  })),
  ...processes.map(process => ({
    key: `process:${process.id}`, type: 'process' as const, id: process.id, title: process.name,
    subtitle: '', aliases: [], summary: process.summary,
    pieces: [piece(process.summary), ...process.steps.map(step => piece(`${step.title}：${step.text}。${step.owner}`)), ...process.questions.map(text => piece(text))],
  })),
].map(entry => ({ ...entry, pieces: entry.pieces.filter(item => item.text.trim()) }));

const semanticEntries = entries.map(entry => prepareSemanticEntry(entry.title, entry.aliases, entry.pieces));
const literalEntries = entries.map(entry => {
  const title = normalized(entry.title), aliases = entry.aliases.map(normalized);
  const pieces = entry.pieces.map(item => ({...item, normalized:normalized(item.text)}));
  return {title, aliases, pieces, full:[title,...aliases,...pieces.map(item=>item.normalized)].join('\n')};
});

function snippet(text: string, tokens: string[]) {
  const haystack = normalized(text);
  const positions = tokens.map(token => haystack.indexOf(token)).filter(index => index >= 0);
  const match = positions.length ? Math.min(...positions) : 0;
  // Most Chinese/Latin text keeps its offsets under NFKC. If normalization
  // changes length, showing the unmodified start is preferable to a bad slice.
  const start = text.length === haystack.length ? Math.max(0, match - 32) : 0;
  const end = Math.min(text.length, start + 145);
  return `${start ? '…' : ''}${text.slice(start, end)}${end < text.length ? '…' : ''}`;
}

export function searchKnowledge(query: string): KnowledgeSearchResult[] {
  const term = normalized(query);
  if (!term) return [];
  const tokens = [...new Set(term.split(/[\s,，、;；]+/u).filter(Boolean))];
  if (!tokens.length) return [];
  return entries.flatMap((entry, index) => {
    const {title, aliases, pieces, full} = literalEntries[index];
    if (!tokens.every(token => full.includes(token))) return [];
    const contentTokens = tokens.filter(token => !title.includes(token) && !aliases.some(alias => alias.includes(token)));
    const scored = pieces.map((item, i) => ({ ...item, i, hits: tokens.filter(token => item.normalized.includes(token)).length, contentHits: contentTokens.filter(token => item.normalized.includes(token)).length, exact: item.normalized.includes(term) }));
    scored.sort((a, b) => b.contentHits - a.contentHits || b.hits - a.hits || Number(b.exact) - Number(a.exact) || a.i - b.i);
    const matched = scored[0];
    const titleExact = title === term;
    const aliasExact = aliases.includes(term);
    const score = (titleExact ? 1000 : 0) + (aliasExact ? 800 : 0) + (title.startsWith(term) ? 300 : 0) +
      tokens.reduce((sum, token) => sum + (title.includes(token) ? 120 : 0) + (aliases.some(alias => alias.includes(token)) ? 40 : 0), 0) +
      (matched?.hits ?? 0) * 5;
    const showMatch = !titleExact && !aliasExact && matched?.hits;
    const result: KnowledgeSearchResult = {
      key: entry.key, type: entry.type, id: entry.id, title: entry.title, subtitle: entry.subtitle,
      excerpt: snippet(showMatch ? matched.text : entry.summary, tokens),
      ...(showMatch && matched.section ? { section: matched.section } : {}),
    };
    return [{ result, score, index }];
  }).sort((a, b) => b.score - a.score || a.index - b.index).map(item => item.result);
}

export function searchHybridKnowledge(query: string, semantic = true): KnowledgeSearchResult[] {
  const literal = searchKnowledge(query), seen = new Set(literal.map(item=>item.key));
  const groups = prepareSemanticQuery(query, semantic);
  if (!groups) return literal;
  const related = entries.flatMap((entry,index) => {
    if (seen.has(entry.key)) return [];
    const match = semanticMatch(semanticEntries[index], groups);
    if (!match) return [];
    return [{key:entry.key,type:entry.type,id:entry.id,title:entry.title,subtitle:entry.subtitle,
      excerpt:match.excerpt, ...(match.section?{section:match.section}:{}), matchKind:'semantic' as const,semanticScore:match.score}];
  }).sort((a,b)=>b.semanticScore-a.semanticScore).slice(0,SEMANTIC_RESULT_LIMIT);
  return [...literal,...related];
}
