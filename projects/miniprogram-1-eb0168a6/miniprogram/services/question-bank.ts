export interface BankShape { kind:string; values:number[]; text:string; color:string; width:number; dash:boolean; fontSize:number; align:string }
export interface BankDiagram { width:number; height:number; shapes:BankShape[] }
export interface BankQuestion { id:string;subject:string;stage:string;grade:string;type:string;stem:string;answer:string;analysis:string;source:string;sourceKind:string;license:string;licenseUrl:string;sourceUrl:string;attribution:string;materialText:string;knowledgeIds?:string[];tags?:string[];difficulty?:string;images?:string[];diagram?:BankDiagram }
export interface BankMatch { question:BankQuestion;score:number;reasons:string[] }
export interface BankResult {status:string;message:string;matches:BankMatch[];returned:number;queryMs:number}
// Shared deterministic display rules. Source data and clipboard content are never rewritten.
export function normalizeQuestionText(source:string):string {
 const entities:Record<string,string>={amp:'&',lt:'<',gt:'>',quot:'"',apos:"'",nbsp:' ',times:'×',divide:'÷',minus:'−',le:'≤',ge:'≥',ne:'≠',middot:'·',deg:'°',pi:'π'};
 return String(source??'').replace(/\r\n?/g,'\n').replace(/[\u200B\uFEFF]/g,'').replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi,(raw,key:string)=>{if(key[0]!=='#')return entities[key]??raw;const n=key[1].toLowerCase()==='x'?parseInt(key.slice(2),16):parseInt(key.slice(1),10);return n>=32&&n<=0x10ffff&&!(n>=0xd800&&n<=0xdfff)?String.fromCodePoint(n):raw;});
}
export function questionTextParts(source:string):{text:string;math:boolean;display:boolean}[] {
 const text=normalizeQuestionText(source),parts:{text:string;math:boolean;display:boolean}[]=[];
 const re=/\$\$([\s\S]*?)\$\$|\\\[([\s\S]*?)\\\]|\\\(([\s\S]*?)\\\)|(?<!\\)\$([^$]*?)(?<!\\)\$/g;
 let end=0;
 for(const m of text.matchAll(re)){
  // A pair of prices in English prose is not a mathematical delimiter pair.
  if(m[4]!==undefined&&/^\d[\d,.]*\s+[A-Za-z]{2,}\b/.test(m[4])&&/^\d/.test(text.slice(m.index!+m[0].length))&&!/\\|[=^_{}]/.test(m[4]))continue;
  parts.push({text:text.slice(end,m.index),math:false,display:false},{text:m[1]??m[2]??m[3]??m[4],math:true,display:m[1]!==undefined||m[2]!==undefined});end=m.index!+m[0].length;
 }
 parts.push({text:text.slice(end),math:false,display:false});return parts;
}
export function readableMath(source:string):string {
 const symbols:Record<string,string>={
 alpha:'α',beta:'β',gamma:'γ',delta:'δ',epsilon:'ε',varepsilon:'ε',zeta:'ζ',eta:'η',theta:'θ',vartheta:'ϑ',iota:'ι',kappa:'κ',lambda:'λ',mu:'μ',nu:'ν',xi:'ξ',omicron:'ο',pi:'π',varpi:'ϖ',rho:'ρ',varrho:'ϱ',sigma:'σ',varsigma:'ς',tau:'τ',upsilon:'υ',phi:'φ',varphi:'φ',chi:'χ',psi:'ψ',omega:'ω',Gamma:'Γ',Delta:'Δ',Theta:'Θ',Lambda:'Λ',Xi:'Ξ',Pi:'Π',Sigma:'Σ',Upsilon:'Υ',Phi:'Φ',Psi:'Ψ',Omega:'Ω',
 square:'□',subsetneq:'⊊',subsetneqq:'⊊',wedge:'∧',vee:'∨',backsim:'∽',section:'§',odot:'⊙',bigvee:'⋁',searrow:'↘',nearrow:'↗',diamond:'◇',vartriangle:'△',centerdot:'·',nmid:'∤',bigcap:'⋂',complement:'∁',lt:'<',gt:'>',diamondsuit:'♦',heartsuit:'♥',cdot:'·',bullet:'·',times:'×',div:'÷',circ:'°',angle:'∠',triangle:'△',le:'≤',leq:'≤',leqslant:'≤',ge:'≥',geq:'≥',geqslant:'≥',ne:'≠',neq:'≠',infty:'∞',perp:'⊥',parallel:'∥',nparallel:'∦',pm:'±',mp:'∓',in:'∈',notin:'∉',ni:'∋',cap:'∩',cup:'∪',subset:'⊂',subseteq:'⊆',supset:'⊃',supseteq:'⊇',emptyset:'∅',varnothing:'∅',mid:'|',vert:'|',Vert:'‖',sim:'∼',simeq:'≃',approx:'≈',equiv:'≡',propto:'∝',because:'∵',therefore:'∴',forall:'∀',exists:'∃',neg:'¬',land:'∧',lor:'∨',to:'→',rightarrow:'→',longrightarrow:'→',leftarrow:'←',longleftarrow:'←',Rightarrow:'⇒',Longrightarrow:'⇒',Leftarrow:'⇐',Longleftarrow:'⇐',leftrightarrow:'↔',longleftrightarrow:'↔',Leftrightarrow:'⇔',Longleftrightarrow:'⇔',rightleftharpoons:'⇌',leftrightharpoons:'⇋',uparrow:'↑',downarrow:'↓',sum:'∑',prod:'∏',int:'∫',iint:'∬',oint:'∮',partial:'∂',nabla:'∇',cdots:'⋯',ldots:'…',dots:'…',vdots:'⋮',ddots:'⋱',prime:'′',degree:'°',ast:'*',star:'☆',oplus:'⊕',otimes:'⊗',cong:'≅',triangleq:'≜',not:'¬',lbrace:'{',rbrace:'}',langle:'〈',rangle:'〉',lceil:'⌈',rceil:'⌉',lfloor:'⌊',rfloor:'⌋',backslash:'\\',textquotesingle:"'",textquotedbl:'"',quad:' ',qquad:' ',enspace:' ',thinspace:' ',hfill:'',break:'\n',newline:'\n',hline:'',top:'⊤',bot:'⊥',Re:'Re',Im:'Im',sin:'sin',cos:'cos',tan:'tan',cot:'cot',sec:'sec',csc:'csc',arcsin:'arcsin',arccos:'arccos',arctan:'arctan',sinh:'sinh',cosh:'cosh',tanh:'tanh',ln:'ln',log:'log',lg:'lg',exp:'exp',lim:'lim',max:'max',min:'min',det:'det',gcd:'gcd',Pr:'P',bmod:' mod ',mod:' mod '
 };
 function group(s:string,start:number):{text:string;end:number}|null {while(/\s/.test(s[start]||'')&&start<s.length)start++;if(s[start]!=='{')return null;let depth=1;for(let i=start+1;i<s.length;i++){if(s[i]==='\\'){i++;continue;}if(s[i]==='{')depth++;if(s[i]==='}'&&--depth===0)return{text:s.slice(start+1,i),end:i+1};}return null;}
 function argument(s:string,start:number,depth=0):{text:string;end:number}|null {
  if(depth>32)return null;while(/\s/.test(s[start]||'')&&start<s.length)start++;
  const g=group(s,start);if(g)return g;if(start>=s.length||/[}$]/.test(s[start]))return null;
  if(s[start]!=='\\')return{text:s[start],end:start+1};
  const m=/^\\([A-Za-z]+|[^A-Za-z])/.exec(s.slice(start));if(!m)return null;let end=start+m[0].length;
  const n=['frac','dfrac','tfrac','binom','stackrel','overset','underset'].includes(m[1])?2:['sqrt','vec','overrightarrow','bar','hat','text','mathrm','mathbf','boldsymbol','mathsf','mathbb'].includes(m[1])?1:0;
  for(let j=0;j<n;j++){const a=argument(s,end,depth+1);if(!a)break;end=a.end;}return{text:s.slice(start,end),end};
 }
 function expand(s:string,depth=0):string {
  if(depth>32)return s;let out='';
  for(let i=0;i<s.length;){
   if(s[i]!=='\\'){if((s[i]==='_'||s[i]==='^')&&s[i+1]==='{'){const g=group(s,i+1);if(g){out+=s[i]+'('+expand(g.text,depth+1)+')';i=g.end;continue;}}out+=s[i++];continue;}
   if(s[i+1]==='\\'){out+='\n';i+=2;continue;}
   const m=/^\\([A-Za-z]+|[^A-Za-z])/.exec(s.slice(i));if(!m){out+=s[i++];continue;}
   const name=m[1];i+=m[0].length;
   if(['left','right','big','Big','bigg','Bigg','bigl','bigr','Bigl','Bigr','limits','nolimits','displaystyle','textstyle','scriptstyle','scriptscriptstyle','rm','bf','it'].includes(name)){if(['left','right'].includes(name)&&s[i]==='.')i++;continue;}
   if(name==='begin'||name==='end'){const g=group(s,i);if(g){i=g.end;if(name==='begin'&&/^(array|tabular)$/.test(g.text)){const cols=group(s,i);if(cols)i=cols.end;}out+='\n';continue;}}
   if(name==='cline'||name==='hspace'||name==='vspace'){const g=group(s,i);if(g){i=g.end;continue;}}
   let rootIndex='';if(name==='sqrt'&&s[i]==='['){const end=s.indexOf(']',i);if(end>=0){rootIndex=s.slice(i+1,end);i=end+1;}}
   const first=argument(s,i);
   if(first&&['frac','dfrac','tfrac','binom','dbinom','tbinom','stackrel','overset','underset'].includes(name)){
    const second=argument(s,first.end);if(second){const a=expand(first.text,depth+1),b=expand(second.text,depth+1);out+=name.endsWith('frac')?'('+a+')/('+b+')':name.endsWith('binom')?'C('+a+','+b+')':b+'〔'+a+'〕';i=second.end;continue;}
   }
   if(first&&['sqrt','mathrm','mathbf','mathit','mathsf','mathtt','mathbb','mathcal','mathscr','text','textrm','textbf','textit','textsf','operatorname','emph','mbox','underline','overline','bar','hat','widehat','tilde','widetilde','vec','overrightarrow','overleftarrow','overleftrightarrow','boxed','fbox','pmod','boldsymbol','uline','mathop','underbrace','overbrace','dot','ddot','xrightarrow'].includes(name)){
    const a=expand(first.text,depth+1);out+=name==='sqrt'?(rootIndex?'root['+rootIndex+'](':'√(')+a+')':name==='vec'||name==='overrightarrow'? '→('+a+')':name==='overleftarrow'?'←('+a+')':name==='overleftrightarrow'?'↔('+a+')':name==='overline'||name==='bar'?'overline('+a+')':name==='hat'||name==='widehat'?'hat('+a+')':name==='tilde'||name==='widetilde'?'~('+a+')':name==='pmod'?' (mod '+a+')':name==='dot'?'dot('+a+')':name==='ddot'?'ddot('+a+')':name==='underbrace'||name==='overbrace'?'('+a+')':name==='xrightarrow'?'→〔'+a+'〕':a;i=first.end;continue;
   }
   if(['multirow','multicolumn'].includes(name)&&first){const second=group(s,first.end),third=second&&group(s,second.end);if(third){out+=expand(third.text,depth+1);i=third.end;continue;}}
   if(Object.prototype.hasOwnProperty.call(symbols,name)){out+=symbols[name];continue;}
   if(name.length===1){out+=/[ ,;:!~]/.test(name)?(name==='!'?'':' '):name;continue;}
   out+='\\'+name;
  }
  return out.replace(/~/g,' ').replace(/ & /g,' | ');
 }
 return questionTextParts(source).map(p=>expand(p.text)).join('');
}

export interface BankKnowledgeNode {id:string;name:string;parentId:string;aliases:string[]}
export function bankKnowledgePath(nodes:BankKnowledgeNode[],id:string):string {const byId=new Map(nodes.map(n=>[n.id,n])),names:string[]=[],seen=new Set<string>();while(byId.has(id)&&!seen.has(id)){seen.add(id);const n=byId.get(id)!;names.unshift(n.name);id=n.parentId}return names.join(' › ')}
export function bankKnowledgeRows(nodes:BankKnowledgeNode[],parentId:string,query='') {const q=query.trim().toLowerCase(),parents=new Set(nodes.map(n=>n.parentId));return nodes.filter(n=>q?[n.name,...(n.aliases||[])].some(v=>v.toLowerCase().includes(q)):n.parentId===parentId).map(n=>({id:n.id,name:q?bankKnowledgePath(nodes,n.id):n.name,hasChildren:parents.has(n.id)}))}
