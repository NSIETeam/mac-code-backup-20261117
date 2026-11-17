// Small, explicit business concept expansion. No model, network call or user-result cache.
// This is deliberately conservative: unfamiliar qualifiers keep the literal search only.
export const normalizeSearch = text => text.normalize('NFKC').toLowerCase().replace(/\s+/gu, ' ').trim();
export const SEMANTIC_RESULT_LIMIT = 8;
export const SEMANTIC_DOCUMENT_LIMIT = 64;
export const SEMANTIC_TEXT_LIMIT = 24576;
const concepts = [
  {terms:['补卡','漏打卡','忘记打卡','忘打卡','忘了打卡','没打上卡','打卡漏了','漏签','考勤异常']},
  {terms:['请假','申请休假','想休一天','想请一天假','有事不能来上班','生病不能上班','病假','事假']},
  {terms:['退费','退款','退学费','不想继续上了','不想继续上课','不想再上课','不续读了','剩余学费退回','退剩下的学费']},
  {terms:['调课','改上课时间','换上课时间','调整上课时间','换个时间上课','课程改时间']},
  {terms:['补课','缺了一节课','漏了一节课','没来上课','缺课补上']},
  {terms:['续费','续班','续报','继续报名','课时快用完了','课快上完了','课程快到期了']},
  {terms:['新生交接','报名交接','新生安排','新生入班','新生建档','新报名学生交给谁','报名后交给谁','报完名接下来','报名之后怎么安排']},
  {terms:['新生回访','首课回访','第一次课后联系家长','第一节课后反馈','上完第一节课','首次上课反馈']},
  {terms:['日报','每天工作汇报','每天的工作汇报','今天工作怎么汇报','今日工作总结','一天工作汇报']},
  {terms:['周报','每周工作汇报','一周的工作总结','本周工作总结']},
  {terms:['投诉','家长不满意','家长有意见','家长抱怨','家长投诉']},
  {terms:['离场检查','闭店','关店','下班关门','最后一个离开','校区关门','走之前关电']},
  {terms:['卫生','打扫教室','清洁教室','教室清洁','校区清洁']},
  {terms:['报销','垫付费用','垫了钱','报交通费','报差旅费']},
  {terms:['交接','交接班','工作转交','工作移交']},
  {terms:['招生','招新','招学生','招收学生']},
  {terms:['教务','排课','安排课程','课程安排']},
  {terms:['考勤','上下班打卡','迟到','早退']},
  {terms:['安全','安全隐患','孩子受伤','学生受伤','突发事故']},
  {terms:['错题','做错的题','做错了的题','错误题目']},
  {terms:['备课','准备上课','课前准备','准备教案']},
  {terms:['学习助教','学习辅导','作业辅导']},
  {terms:['生活助教','生活照顾','照看学生生活']},
];
const fillers = ['应该怎么办','应该怎么做','怎么办','怎么处理','如何处理','怎么操作','如何操作','怎么做','如何办理','怎么办理','怎么申请','如何申请','应该找谁','找谁负责','需要什么材料','需要哪些材料','有什么要求','有哪些要求','相关规定','相关制度','相关流程','流程是什么','怎么规定','怎么写','如何写','怎么查','哪里查','请问','帮我查一下','帮我查','我想了解','想了解','关于','有关','老师','员工','学生','家长','校区','学校','公司','今天','昨天','一下','应该','需要','可以','怎样','怎么','如何','的','了','想','要','我','吗','呢','呀','该'];
const variants = concepts.flatMap((concept, id) => concept.terms.map(term => ({id, term}))).sort((a,b) => b.term.length-a.term.length);

export function prepareSemanticQuery(query, enabled = true) {
  if (!enabled || query.length > 200) return null;
  let remaining = normalizeSearch(query).replace(/[\s，,。？?！!；;、：:]/gu, '');
  // Do not silently discard identifiers, dates, quoted clauses or negation.
  if (!remaining || /[a-z0-9]|不是|不要|不需要|无需|不用|不退费|不退款/u.test(remaining)) return null;
  const ids = new Set();
  for (const {id, term} of variants) if (remaining.includes(term)) { ids.add(id); remaining = remaining.split(term).join(''); }
  for (const filler of fillers) remaining = remaining.split(filler).join('');
  if (remaining || !ids.size || ids.size > 3) return null;
  return [...ids].map(id => concepts[id].terms);
}

export function prepareSemanticEntry(title, aliases, pieces) {
  let budget = SEMANTIC_TEXT_LIMIT;
  const bounded = [];
  for (const piece of pieces) {
    if (budget <= 0) break;
    const text = piece.text.slice(0, budget); budget -= text.length;
    // Short chunks keep the returned excerpt close to actual supporting words.
    for (let start=0; start<text.length; start+=480) {
      const chunk = text.slice(start, start+512);
      bounded.push({...piece, text:chunk, normalized:normalizeSearch(chunk)});
    }
  }
  return {title:normalizeSearch(title), aliases:normalizeSearch(aliases.join(' ').slice(0,2048)), pieces:bounded};
}

export function semanticMatch(entry, groups) {
  if (!groups) return null;
  const contains = (text, terms) => terms.some(term => text.includes(term));
  const all = [entry.title, entry.aliases, ...entry.pieces.map(piece => piece.normalized)].join('\n');
  if (!groups.every(terms => contains(all, terms))) return null;
  const ranked = entry.pieces.map((piece,index) => ({piece,index,hits:groups.filter(terms=>contains(piece.normalized,terms)).length})).sort((a,b)=>b.hits-a.hits||a.index-b.index);
  const best = ranked[0];
  // Prefer dedicated articles to broad job descriptions with incidental mentions.
  const score = groups.reduce((sum,terms)=>sum+(contains(entry.title,terms)?100:0)+(terms.some(term=>entry.title.startsWith(term))?30:0)+(contains(entry.aliases,terms)?12:0),0)+(best?.hits??0)*3;
  const text = best?.hits ? best.piece.text : entry.pieces[0]?.text || '';
  const folded = normalizeSearch(text);
  const positions = groups.flat().map(term=>folded.indexOf(term)).filter(index=>index>=0);
  const start = folded.length===text.length && positions.length ? Math.max(0,Math.min(...positions)-30) : 0;
  return {score, excerpt:`${start?'…':''}${text.slice(start,start+145)}${start+145<text.length?'…':''}`, section:best?.hits ? best.piece.section : undefined};
}

// Exact results always precede related expressions across reference and private sources.
export function mergeSearchResults(...sources) {
  const results = sources.flat();
  return [...results.filter(item=>item.matchKind!=='semantic'), ...results.filter(item=>item.matchKind==='semantic').sort((a,b)=>(b.semanticScore||0)-(a.semanticScore||0)).slice(0,SEMANTIC_RESULT_LIMIT)]
    .map(({semanticScore,...item})=>item);
}
