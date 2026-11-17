// BEGIN SHARED QUESTION TEXT
// Shared deterministic display rules. Source data and clipboard content are never rewritten.
export function normalizeQuestionText(source) {
    const entities = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', times: '×', divide: '÷', minus: '−', le: '≤', ge: '≥', ne: '≠', middot: '·', deg: '°', pi: 'π' };
    return String(source ?? '').replace(/\r\n?/g, '\n').replace(/[\u200B\uFEFF]/g, '').replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (raw, key) => { if (key[0] !== '#')
        return entities[key] ?? raw; const n = key[1].toLowerCase() === 'x' ? parseInt(key.slice(2), 16) : parseInt(key.slice(1), 10); return n >= 32 && n <= 0x10ffff && !(n >= 0xd800 && n <= 0xdfff) ? String.fromCodePoint(n) : raw; });
}
export function questionTextParts(source) {
    const text = normalizeQuestionText(source), parts = [];
    const re = /\$\$([\s\S]*?)\$\$|\\\[([\s\S]*?)\\\]|\\\(([\s\S]*?)\\\)|(?<!\\)\$([^$]*?)(?<!\\)\$/g;
    let end = 0;
    for (const m of text.matchAll(re)) {
        // A pair of prices in English prose is not a mathematical delimiter pair.
        if (m[4] !== undefined && /^\d[\d,.]*\s+[A-Za-z]{2,}\b/.test(m[4]) && /^\d/.test(text.slice(m.index + m[0].length)) && !/\\|[=^_{}]/.test(m[4]))
            continue;
        parts.push({ text: text.slice(end, m.index), math: false, display: false }, { text: m[1] ?? m[2] ?? m[3] ?? m[4], math: true, display: m[1] !== undefined || m[2] !== undefined });
        end = m.index + m[0].length;
    }
    parts.push({ text: text.slice(end), math: false, display: false });
    return parts;
}
export function readableMath(source) {
    const symbols = {
        alpha: 'α', beta: 'β', gamma: 'γ', delta: 'δ', epsilon: 'ε', varepsilon: 'ε', zeta: 'ζ', eta: 'η', theta: 'θ', vartheta: 'ϑ', iota: 'ι', kappa: 'κ', lambda: 'λ', mu: 'μ', nu: 'ν', xi: 'ξ', omicron: 'ο', pi: 'π', varpi: 'ϖ', rho: 'ρ', varrho: 'ϱ', sigma: 'σ', varsigma: 'ς', tau: 'τ', upsilon: 'υ', phi: 'φ', varphi: 'φ', chi: 'χ', psi: 'ψ', omega: 'ω', Gamma: 'Γ', Delta: 'Δ', Theta: 'Θ', Lambda: 'Λ', Xi: 'Ξ', Pi: 'Π', Sigma: 'Σ', Upsilon: 'Υ', Phi: 'Φ', Psi: 'Ψ', Omega: 'Ω',
        square: '□', subsetneq: '⊊', subsetneqq: '⊊', wedge: '∧', vee: '∨', backsim: '∽', section: '§', odot: '⊙', bigvee: '⋁', searrow: '↘', nearrow: '↗', diamond: '◇', vartriangle: '△', centerdot: '·', nmid: '∤', bigcap: '⋂', complement: '∁', lt: '<', gt: '>', diamondsuit: '♦', heartsuit: '♥', cdot: '·', bullet: '·', times: '×', div: '÷', circ: '°', angle: '∠', triangle: '△', le: '≤', leq: '≤', leqslant: '≤', ge: '≥', geq: '≥', geqslant: '≥', ne: '≠', neq: '≠', infty: '∞', perp: '⊥', parallel: '∥', nparallel: '∦', pm: '±', mp: '∓', in: '∈', notin: '∉', ni: '∋', cap: '∩', cup: '∪', subset: '⊂', subseteq: '⊆', supset: '⊃', supseteq: '⊇', emptyset: '∅', varnothing: '∅', mid: '|', vert: '|', Vert: '‖', sim: '∼', simeq: '≃', approx: '≈', equiv: '≡', propto: '∝', because: '∵', therefore: '∴', forall: '∀', exists: '∃', neg: '¬', land: '∧', lor: '∨', to: '→', rightarrow: '→', longrightarrow: '→', leftarrow: '←', longleftarrow: '←', Rightarrow: '⇒', Longrightarrow: '⇒', Leftarrow: '⇐', Longleftarrow: '⇐', leftrightarrow: '↔', longleftrightarrow: '↔', Leftrightarrow: '⇔', Longleftrightarrow: '⇔', rightleftharpoons: '⇌', leftrightharpoons: '⇋', uparrow: '↑', downarrow: '↓', sum: '∑', prod: '∏', int: '∫', iint: '∬', oint: '∮', partial: '∂', nabla: '∇', cdots: '⋯', ldots: '…', dots: '…', vdots: '⋮', ddots: '⋱', prime: '′', degree: '°', ast: '*', star: '☆', oplus: '⊕', otimes: '⊗', cong: '≅', triangleq: '≜', not: '¬', lbrace: '{', rbrace: '}', langle: '〈', rangle: '〉', lceil: '⌈', rceil: '⌉', lfloor: '⌊', rfloor: '⌋', backslash: '\\', textquotesingle: "'", textquotedbl: '"', quad: ' ', qquad: ' ', enspace: ' ', thinspace: ' ', hfill: '', break: '\n', newline: '\n', hline: '', top: '⊤', bot: '⊥', Re: 'Re', Im: 'Im', sin: 'sin', cos: 'cos', tan: 'tan', cot: 'cot', sec: 'sec', csc: 'csc', arcsin: 'arcsin', arccos: 'arccos', arctan: 'arctan', sinh: 'sinh', cosh: 'cosh', tanh: 'tanh', ln: 'ln', log: 'log', lg: 'lg', exp: 'exp', lim: 'lim', max: 'max', min: 'min', det: 'det', gcd: 'gcd', Pr: 'P', bmod: ' mod ', mod: ' mod '
    };
    function group(s, start) { while (/\s/.test(s[start] || '') && start < s.length)
        start++; if (s[start] !== '{')
        return null; let depth = 1; for (let i = start + 1; i < s.length; i++) {
        if (s[i] === '\\') {
            i++;
            continue;
        }
        if (s[i] === '{')
            depth++;
        if (s[i] === '}' && --depth === 0)
            return { text: s.slice(start + 1, i), end: i + 1 };
    } return null; }
    function argument(s, start, depth = 0) {
        if (depth > 32)
            return null;
        while (/\s/.test(s[start] || '') && start < s.length)
            start++;
        const g = group(s, start);
        if (g)
            return g;
        if (start >= s.length || /[}$]/.test(s[start]))
            return null;
        if (s[start] !== '\\')
            return { text: s[start], end: start + 1 };
        const m = /^\\([A-Za-z]+|[^A-Za-z])/.exec(s.slice(start));
        if (!m)
            return null;
        let end = start + m[0].length;
        const n = ['frac', 'dfrac', 'tfrac', 'binom', 'stackrel', 'overset', 'underset'].includes(m[1]) ? 2 : ['sqrt', 'vec', 'overrightarrow', 'bar', 'hat', 'text', 'mathrm', 'mathbf', 'boldsymbol', 'mathsf', 'mathbb'].includes(m[1]) ? 1 : 0;
        for (let j = 0; j < n; j++) {
            const a = argument(s, end, depth + 1);
            if (!a)
                break;
            end = a.end;
        }
        return { text: s.slice(start, end), end };
    }
    function expand(s, depth = 0) {
        if (depth > 32)
            return s;
        let out = '';
        for (let i = 0; i < s.length;) {
            if (s[i] !== '\\') {
                if ((s[i] === '_' || s[i] === '^') && s[i + 1] === '{') {
                    const g = group(s, i + 1);
                    if (g) {
                        out += s[i] + '(' + expand(g.text, depth + 1) + ')';
                        i = g.end;
                        continue;
                    }
                }
                out += s[i++];
                continue;
            }
            if (s[i + 1] === '\\') {
                out += '\n';
                i += 2;
                continue;
            }
            const m = /^\\([A-Za-z]+|[^A-Za-z])/.exec(s.slice(i));
            if (!m) {
                out += s[i++];
                continue;
            }
            const name = m[1];
            i += m[0].length;
            if (['left', 'right', 'big', 'Big', 'bigg', 'Bigg', 'bigl', 'bigr', 'Bigl', 'Bigr', 'limits', 'nolimits', 'displaystyle', 'textstyle', 'scriptstyle', 'scriptscriptstyle', 'rm', 'bf', 'it'].includes(name)) {
                if (['left', 'right'].includes(name) && s[i] === '.')
                    i++;
                continue;
            }
            if (name === 'begin' || name === 'end') {
                const g = group(s, i);
                if (g) {
                    i = g.end;
                    if (name === 'begin' && /^(array|tabular)$/.test(g.text)) {
                        const cols = group(s, i);
                        if (cols)
                            i = cols.end;
                    }
                    out += '\n';
                    continue;
                }
            }
            if (name === 'cline' || name === 'hspace' || name === 'vspace') {
                const g = group(s, i);
                if (g) {
                    i = g.end;
                    continue;
                }
            }
            let rootIndex = '';
            if (name === 'sqrt' && s[i] === '[') {
                const end = s.indexOf(']', i);
                if (end >= 0) {
                    rootIndex = s.slice(i + 1, end);
                    i = end + 1;
                }
            }
            const first = argument(s, i);
            if (first && ['frac', 'dfrac', 'tfrac', 'binom', 'dbinom', 'tbinom', 'stackrel', 'overset', 'underset'].includes(name)) {
                const second = argument(s, first.end);
                if (second) {
                    const a = expand(first.text, depth + 1), b = expand(second.text, depth + 1);
                    out += name.endsWith('frac') ? '(' + a + ')/(' + b + ')' : name.endsWith('binom') ? 'C(' + a + ',' + b + ')' : b + '〔' + a + '〕';
                    i = second.end;
                    continue;
                }
            }
            if (first && ['sqrt', 'mathrm', 'mathbf', 'mathit', 'mathsf', 'mathtt', 'mathbb', 'mathcal', 'mathscr', 'text', 'textrm', 'textbf', 'textit', 'textsf', 'operatorname', 'emph', 'mbox', 'underline', 'overline', 'bar', 'hat', 'widehat', 'tilde', 'widetilde', 'vec', 'overrightarrow', 'overleftarrow', 'overleftrightarrow', 'boxed', 'fbox', 'pmod', 'boldsymbol', 'uline', 'mathop', 'underbrace', 'overbrace', 'dot', 'ddot', 'xrightarrow'].includes(name)) {
                const a = expand(first.text, depth + 1);
                out += name === 'sqrt' ? (rootIndex ? 'root[' + rootIndex + '](' : '√(') + a + ')' : name === 'vec' || name === 'overrightarrow' ? '→(' + a + ')' : name === 'overleftarrow' ? '←(' + a + ')' : name === 'overleftrightarrow' ? '↔(' + a + ')' : name === 'overline' || name === 'bar' ? 'overline(' + a + ')' : name === 'hat' || name === 'widehat' ? 'hat(' + a + ')' : name === 'tilde' || name === 'widetilde' ? '~(' + a + ')' : name === 'pmod' ? ' (mod ' + a + ')' : name === 'dot' ? 'dot(' + a + ')' : name === 'ddot' ? 'ddot(' + a + ')' : name === 'underbrace' || name === 'overbrace' ? '(' + a + ')' : name === 'xrightarrow' ? '→〔' + a + '〕' : a;
                i = first.end;
                continue;
            }
            if (['multirow', 'multicolumn'].includes(name) && first) {
                const second = group(s, first.end), third = second && group(s, second.end);
                if (third) {
                    out += expand(third.text, depth + 1);
                    i = third.end;
                    continue;
                }
            }
            if (Object.prototype.hasOwnProperty.call(symbols, name)) {
                out += symbols[name];
                continue;
            }
            if (name.length === 1) {
                out += /[ ,;:!~]/.test(name) ? (name === '!' ? '' : ' ') : name;
                continue;
            }
            out += '\\' + name;
        }
        return out.replace(/~/g, ' ').replace(/ & /g, ' | ');
    }
    return questionTextParts(source).map(p => expand(p.text)).join('');
}
// END SHARED QUESTION TEXT
const $=id=>document.getElementById(id),subjects=['数学','语文','英语','物理','化学','生物','政治','历史','地理'],stages=['高中','初中','小学'],types=['选择题','填空题','解答题','综合题'];
const apiBase=new URL('../api/',import.meta.url),key='siyue_web_token';
let epoch=0,seen=[],minePage=1,user=null,currentView='search',knowledgeNodes=[],selectedKnowledge='',catalogEpoch=0,reviewJob=null,publishRequest='',selectedDrafts=new Set(),intakeEpoch=0,learnerEpoch=0,learnerGroups=[],selectedLearners=new Set();
const knowledgeCache=new Map(),mediaCache=new Map();
const token=()=>sessionStorage.getItem(key)||'';
const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;return n;};
function message(id,text,error=false){$(id).textContent=text;$(id).className=error?'error':'muted';}
function fill(id,values,all=false){if(all){const n=el('option','全部题型');n.value='';$(id).append(n);}for(const v of values){const n=el('option',v);n.value=v;$(id).append(n);}}
fill('search-subject',subjects);fill('search-stage',stages);fill('search-type',types,true);
async function api(path,{method='GET',body}={}){const identity=token();let response;try{response=await fetch(new URL(path,apiBase),{method,headers:{Authorization:'Bearer '+identity,...(body instanceof FormData?{}:{'Content-Type':'application/json'})},body:body instanceof FormData?body:body===undefined?undefined:JSON.stringify(body)});}catch{throw Error('连接失败，请稍后重试');}if(identity!==token())throw Error('账号已切换，请重新操作');if(response.status===401){sessionStorage.removeItem(key);showLogin();throw Error('登录已过期，请重新登录');}if(!response.ok){const data=await response.json().catch(()=>({}));throw Error(data.message||'请求失败，请稍后重试');}return response.status===204?null:response.json();}
function clearMedia(){for(const value of mediaCache.values())value.then(url=>URL.revokeObjectURL(url)).catch(()=>{});mediaCache.clear();}
function showLogin(){epoch++;catalogEpoch++;intakeEpoch++;learnerEpoch++;user=null;knowledgeNodes=[];knowledgeCache.clear();clearMedia();$('login-panel').hidden=false;$('workspace').hidden=true;$('bank-nav').hidden=true;$('account-tools').hidden=true;$('results').replaceChildren();$('my-questions').replaceChildren();$('my-intakes').replaceChildren();$('intake-review').replaceChildren();$('intake-jobs').replaceChildren();$('login-password').value='';reviewJob=null;seen=[];selectedLearners.clear();if($('learner-dialog').open)$('learner-dialog').close();}
async function enter(){if(!token()){showLogin();return;}try{user=await api('me');const status=await api('question-bank');$('account-name').textContent=user.name;$('catalog-count').textContent=status.count.toLocaleString();$('login-panel').hidden=true;$('workspace').hidden=false;$('bank-nav').hidden=false;$('account-tools').hidden=false;view('search');await suggest();}catch(e){showLogin();message('login-message',e.message,true);}}
$('login-form').addEventListener('submit',async event=>{event.preventDefault();const button=event.currentTarget.querySelector('button');button.disabled=true;try{const data=await api('auth/password',{method:'POST',body:{account:$('login-account').value.trim(),password:$('login-password').value}});sessionStorage.setItem(key,data.token);$('login-password').value='';await enter();}catch(e){message('login-message',e.message,true);}finally{button.disabled=false;}});
$('logout').addEventListener('click',async()=>{try{await api('logout',{method:'POST'});}catch{}finally{sessionStorage.removeItem(key);showLogin();}});
function view(name){epoch++;intakeEpoch++;currentView=name;for(const n of ['search','upload','mine'])$(`view-${n}`).hidden=n!==name;document.querySelectorAll('[data-view]').forEach(n=>n.classList.toggle('active',n.dataset.view===name));if(name==='mine')void mine();if(name==='upload')void loadIntakes();}
document.querySelectorAll('[data-view]').forEach(n=>n.addEventListener('click',()=>view(n.dataset.view)));
async function loadKnowledge(subject){if(!knowledgeCache.has(subject))knowledgeCache.set(subject,api('question-bank/knowledge?subject='+encodeURIComponent(subject)).catch(e=>{knowledgeCache.delete(subject);throw e;}));return knowledgeCache.get(subject);}
function knowledgePath(id){const map=new Map(knowledgeNodes.map(n=>[n.id,n])),names=[],visited=new Set();while(map.has(id)&&!visited.has(id)){visited.add(id);const n=map.get(id);names.unshift(n.name);id=n.parentId;}return names.join(' › ');}
function chooseKnowledge(node){selectedKnowledge=node.id;$('topic').value=node.name;invalidate();renderKnowledge();if(!window.matchMedia('(min-width:801px)').matches)$('knowledge-directory').open=false;void search(false);}
function renderKnowledge(){const root=$('knowledge-tree'),opened=new Set([...root.querySelectorAll('details[open]')].map(n=>n.dataset.id));root.replaceChildren();if($('search-stage').value!=='高中'){root.append(el('p','此目录为高中知识点，当前学段请直接输入关键词。','muted'));return;}const query=$('knowledge-search').value.trim().toLowerCase(),byParent=new Map();for(const n of knowledgeNodes){if(!byParent.has(n.parentId))byParent.set(n.parentId,[]);byParent.get(n.parentId).push(n);}if(query){const matches=knowledgeNodes.filter(n=>[n.name,...n.aliases].some(s=>s.toLowerCase().includes(query)));for(const n of matches){const b=el('button',knowledgePath(n.id),n.id===selectedKnowledge?'selected':'');b.type='button';b.addEventListener('click',()=>chooseKnowledge(n));root.append(b);}if(!matches.length)root.append(el('p','没有匹配的知识点，可直接输入关键词。','muted'));return;}
function branch(parent,target,path=new Set()){for(const n of byParent.get(parent)||[]){if(path.has(n.id))continue;const next=new Set(path);next.add(n.id);if(byParent.has(n.id)){const d=el('details'),summary=el('summary',n.name),children=el('div');d.dataset.id=n.id;d.append(summary,children);d.open=opened.has(n.id)||selectedKnowledge.startsWith(n.id+'.');branch(n.id,children,next);target.append(d);}else{const b=el('button',n.name,n.id===selectedKnowledge?'selected':'');b.type='button';b.addEventListener('click',()=>chooseKnowledge(n));target.append(b);}}}branch($('search-subject').value,root);if(!root.childElementCount)root.append(el('p','该学科暂无目录，可直接输入关键词。','muted'));}
async function suggest(){const seq=++catalogEpoch;try{const nodes=await loadKnowledge($('search-subject').value);if(seq!==catalogEpoch)return;knowledgeNodes=nodes;renderKnowledge();}catch{if(seq===catalogEpoch)$('knowledge-tree').replaceChildren(el('p','目录暂不可用，可直接输入关键词。','muted'));}}
$('knowledge-search').addEventListener('input',renderKnowledge);$('knowledge-directory').open=window.matchMedia('(min-width:801px)').matches;
function filters(open){$('filters-panel').hidden=!open;$('filter-toggle').setAttribute('aria-expanded',String(open));}
$('filter-toggle').addEventListener('click',()=>filters($('filters-panel').hidden));$('filters-done').addEventListener('click',()=>{filters(false);$('filter-toggle').focus();});
document.addEventListener('click',e=>{if(!e.target.closest('.query-line'))filters(false);});document.addEventListener('keydown',e=>{if(e.key==='Escape')filters(false);});
function filterSummary(){$('filter-summary').textContent=[$('search-subject').value,$('search-stage').value,$('search-type').value].filter(Boolean).join(' · ');}
function invalidate(){epoch++;seen=[];$('results').replaceChildren();$('next-batch').hidden=true;$('search-button').disabled=false;message('search-message','');}
for(const id of ['search-subject','search-stage','search-type'])$(id).addEventListener('change',()=>{invalidate();filterSummary();if(id!=='search-type'){selectedKnowledge='';$('knowledge-search').value='';$('topic').value='';knowledgeNodes=[];$('knowledge-tree').replaceChildren();}void suggest();});
$('topic').addEventListener('input',()=>{invalidate();selectedKnowledge='';});filterSummary();
$('search-form').addEventListener('submit',e=>{e.preventDefault();filters(false);void search(false);});$('next-batch').addEventListener('click',()=>void search(true));
async function search(next){const topic=$('topic').value.trim();if(!topic)return;if(!next)seen=[];const request=++epoch;$('search-button').disabled=true;$('next-batch').hidden=true;message('search-message','正在查找…');try{const result=await api('question-bank/recommendations',{method:'POST',body:{subject:$('search-subject').value,stage:$('search-stage').value,type:$('search-type').value,topic,limit:5,excludeIds:seen.slice(-100)}});if(request!==epoch)return;seen=[...new Set([...seen,...result.matches.map(m=>m.question.id)])].slice(-100);$('results').replaceChildren(...result.matches.map(m=>card(m.question)));message('search-message',result.matches.length?`找到 ${result.returned} 道题`:result.message);$('next-batch').hidden=!result.matches.length;math($('results'));}catch(e){if(request===epoch)message('search-message',e.message,true);}finally{if(request===epoch)$('search-button').disabled=false;}}
function math(root){for(const target of (root.matches?.('.stem')?[root]:root.querySelectorAll('.stem'))){if(target.dataset.mathRendered)continue;const source=target.textContent;target.replaceChildren();for(const part of questionTextParts(source)){if(!part.math){target.append(document.createTextNode(readableMath(part.text)));continue;}const span=document.createElement('span');try{if(!window.katex)throw Error('Formula renderer unavailable');span.innerHTML=window.katex.renderToString(part.text,{displayMode:part.display,trust:false,throwOnError:true,strict:'ignore',maxExpand:1000});}catch{span.textContent=readableMath('$'+part.text+'$');span.className='math-fallback';span.title='公式使用线性记号显示';}target.append(span);}target.dataset.mathRendered='1';}}
function diagram(d){const ns='http://www.w3.org/2000/svg',root=document.createElementNS(ns,'svg');root.setAttribute('viewBox','0 0 360 270');root.setAttribute('class','question-diagram');root.setAttribute('role','img');root.setAttribute('aria-label','题目几何示意图');for(const s of d.shapes||[]){const v=s.values;if(!Array.isArray(v)||v.some(x=>!Number.isFinite(x))||!['line','path','circle','text'].includes(s.kind))continue;const color=({'#315e52':'#1d1d1f','#698675':'#77777d'})[s.color?.toLowerCase()]||s.color,n=document.createElementNS(ns,s.kind),set=(k,v)=>n.setAttribute(k,String(v));if(s.kind==='line'&&v.length===4)['x1','y1','x2','y2'].forEach((k,i)=>set(k,v[i]));else if(s.kind==='circle'&&v.length===3){['cx','cy','r'].forEach((k,i)=>set(k,v[i]));set('fill',color);}else if(s.kind==='path'&&v.length%2===0){set('d',v.reduce((a,b,i)=>a+(i%2?' ':i?' L':'M')+b,''));set('fill','none');}else if(s.kind==='text'&&v.length===2){set('x',v[0]);set('y',v[1]);set('font-size',s.fontSize);set('text-anchor',s.align==='center'?'middle':'start');set('fill',color);n.textContent=s.text;}else continue;if(s.kind==='line'||s.kind==='path'){set('stroke',color);set('stroke-width',s.width);if(s.dash)set('stroke-dasharray','4 4');}root.append(n);}return root;}
function details(label,...children){const d=el('details');d.append(el('summary',label),...children);d.addEventListener('toggle',()=>{if(d.open)math(d);});return d;}
async function media(path){if(!/^question-bank\/intakes\/[a-f0-9-]{36}\/assets\/(?:page-\d{1,3}\.png|source\.(?:png|jpg|jpeg|webp|bmp|tif|tiff|pdf|docx|doc|txt|md))$/.test(path))throw Error('原图地址无效');if(!mediaCache.has(path))mediaCache.set(path,(async()=>{const response=await fetch(new URL(path,apiBase),{headers:{Authorization:'Bearer '+token()}});if(!response.ok)throw Error('原文件暂不可用');return URL.createObjectURL(await response.blob());})().catch(e=>{mediaCache.delete(path);throw e;}));return mediaCache.get(path);}
function images(root,paths){for(const path of [...new Set(paths||[])]){const figure=el('figure',null,'source-image'),img=el('img');img.alt='题目原图';img.loading='lazy';figure.append(img);root.append(figure);void media(path).then(url=>{if(img.isConnected)img.src=url;}).catch(e=>{figure.replaceChildren(el('p',e.message,'muted'));});}}
function card(q){const root=el('article',null,'question-card');root.dataset.questionId=q.id;const head=el('header');head.append(el('strong',`${q.subject} · ${q.stage} · ${q.type}`),el('span',q.sourceKind==='community'?'员工上传':q.sourceKind==='original'?'自编变式':'来源原题','pill'));root.append(head);const paths=(q.knowledgeIds||[]).map(knowledgePath).filter(Boolean),meta=[...new Set(paths.length?paths:q.tags||[])].slice(0,3).join('；');if(meta)root.append(el('p',meta,'question-meta'));if(q.materialText)root.append(details('阅读材料',el('div',q.materialText,'stem')));root.append(el('div',q.stem,'stem'));images(root,q.images);if(q.diagram)root.append(diagram(q.diagram));root.append(details('答案与解析',el('h4','参考答案'),el('div',q.answer,'stem'),el('h4','解析'),el('div',q.analysis||'题源未提供解析','stem')));root.append(details('出处与许可',el('p',[q.source,q.license,q.attribution,q.sourceUrl,q.licenseUrl].filter(Boolean).join('\n'),'provenance')));return root;}
let uploading=false;
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
$('choose-files').addEventListener('click',()=>$('intake-files').click());$('take-photo').addEventListener('click',()=>$('camera-file').click());
$('drop-zone').addEventListener('click',e=>{if(!e.target.closest('button'))$('intake-files').click();});$('drop-zone').addEventListener('keydown',e=>{if(e.target===$('drop-zone')&&['Enter',' '].includes(e.key)){e.preventDefault();$('intake-files').click();}});
for(const name of ['dragenter','dragover'])$('drop-zone').addEventListener(name,e=>{e.preventDefault();e.dataTransfer.dropEffect='copy';$('drop-zone').classList.add('dragging');});
$('drop-zone').addEventListener('dragleave',e=>{if(!$('drop-zone').contains(e.relatedTarget))$('drop-zone').classList.remove('dragging');});$('drop-zone').addEventListener('drop',e=>{e.preventDefault();$('drop-zone').classList.remove('dragging');void uploadFiles([...e.dataTransfer.files]);});
for(const id of ['intake-files','camera-file'])$(id).addEventListener('change',e=>{void uploadFiles([...e.target.files]);e.target.value='';});
async function uploadFiles(files){if(!files.length)return;if(uploading){message('intake-message','正在上传上一批文件，请稍后再添加。');return;}if(files.length>10){message('intake-message','一次最多选择10份文件',true);return;}uploading=true;const identity=token();reviewJob=null;$('intake-review').replaceChildren();$('choose-files').disabled=true;$('take-photo').disabled=true;const errors=[];try{for(let i=0;i<files.length;i++){if(identity!==token())break;const file=files[i];message('intake-message',`正在上传 ${i+1}/${files.length}：${file.name}`);if(file.size>20*1024*1024){errors.push(file.name+' 超过20MB');continue;}try{const body=new FormData();body.append('file',file);body.append('subject',$('search-subject').value);body.append('stage',$('search-stage').value);await api('question-bank/intakes',{method:'POST',body});}catch(e){errors.push(file.name+'：'+e.message);}}message('intake-message',errors.length?errors.join('；'):'上传完成，正在自动识别。',errors.length>0);void loadIntakes();}finally{uploading=false;$('choose-files').disabled=false;$('take-photo').disabled=false;}}
function jobRow(job){const row=el('div',null,'intake-job'),info=el('div');info.append(el('strong',job.fileName),el('p',job.message,'muted'));row.append(info);if(['review','published','failed'].includes(job.state)){const b=el('button',job.state==='review'?'查看识别结果':job.state==='published'?'查看记录':'查看失败原因','secondary');b.addEventListener('click',()=>{view('upload');void openReview(job.id);});row.append(b);}else row.append(el('span',job.state==='queued'?'等待识别':'识别中','pill'));return row;}
async function loadIntakes(){const seq=++intakeEpoch;do{try{const jobs=await api('question-bank/intakes');if(seq!==intakeEpoch||currentView!=='upload')return;$('intake-jobs').replaceChildren(...jobs.map(jobRow));if(reviewJob&&jobs.find(j=>j.id===reviewJob.id)?.state==='processing')reviewJob=null;if(!jobs.some(j=>['queued','processing'].includes(j.state))){if($('intake-message').textContent==='上传完成，正在自动识别。')message('intake-message','识别完成，请查看结果并确认入库。');return;}await delay(1800);}catch(e){if(seq===intakeEpoch)message('intake-message',e.message,true);return;}}while(seq===intakeEpoch&&currentView==='upload');}
function draftReady(d){return d.question.stem.trim()&&d.question.answer.trim();}
function publishLabel(){const count=reviewJob?.drafts.filter(d=>selectedDrafts.has(d.key)&&draftReady(d)).length||0;$('publish-intake').textContent=`确认入库 ${count} 道`;$('publish-intake').disabled=!count;}
async function openReview(id){try{const job=await api('question-bank/intakes/'+id);if(currentView!=='upload')return;reviewJob=job;publishRequest=crypto.randomUUID();selectedDrafts=new Set(job.drafts.filter(draftReady).map(d=>d.key));renderReview();$('intake-review').scrollIntoView({behavior:'smooth',block:'start'});}catch(e){message('intake-message',e.message,true);}}
function draftField(d,label,field,rows=2){const l=el('label',label),input=el('textarea');input.rows=rows;input.value=d.question[field]||'';input.maxLength=field==='stem'||field==='analysis'?40000:20000;input.addEventListener('input',()=>{d.question[field]=input.value;publishRequest=crypto.randomUUID();const checkbox=document.querySelector(`[data-draft-key="${d.key}"]`);if(draftReady(d)){selectedDrafts.add(d.key);checkbox.checked=true;}else{selectedDrafts.delete(d.key);checkbox.checked=false;}const state=checkbox.closest('.review-card').querySelector('.pill');state.textContent=d.question.answer.trim()?'已填写答案':'待补答案';publishLabel();});l.append(input);return l;}
function draftSelect(d,label,field,values){const l=el('label',label),input=el('select');for(const v of values){const opt=el('option',v);opt.value=v;input.append(opt);}input.value=d.question[field];input.addEventListener('change',()=>{d.question[field]=input.value;publishRequest=crypto.randomUUID();});l.append(input);return l;}
function renderReview(){const job=reviewJob,root=$('intake-review');root.replaceChildren();const heading=el('div',null,'section-heading'),source=el('button','查看原文件','secondary');source.addEventListener('click',async()=>{try{const url=await media(job.source);const a=el('a');a.href=url;a.download=job.fileName;a.click();}catch(e){message('intake-message',e.message,true);}});heading.append(el('h2',job.fileName),source);root.append(heading,el('p',job.message,'muted'));if(job.state!=='review')return;
for(const [index,d]of job.drafts.entries()){const row=el('article',null,'review-card'),label=el('label',null,'review-check'),check=el('input');check.type='checkbox';check.checked=selectedDrafts.has(d.key);check.dataset.draftKey=d.key;check.addEventListener('change',()=>{if(check.checked&&!draftReady(d)){check.checked=false;editor.open=true;message('intake-message','请补齐题干与参考答案后入库',true);return;}check.checked?selectedDrafts.add(d.key):selectedDrafts.delete(d.key);publishLabel();});label.append(check,el('strong',`第 ${index+1} 题`),el('span',d.question.answer?'已识别答案':'待补答案','pill'));row.append(label,el('div',d.question.stem||'图片文字不清晰，请对照原图补充题干。','stem'));images(row,d.images);const fields=el('div',null,'fields three');fields.append(draftSelect(d,'学科','subject',subjects),draftSelect(d,'学段','stage',stages),draftSelect(d,'题型','type',types));const editor=details(d.question.answer?'编辑识别结果':'补充答案／修正识别结果',fields,draftField(d,'题干','stem',4),draftField(d,'参考答案','answer'),draftField(d,'解析（选填）','analysis',3));row.append(editor);root.append(row);}
const b=el('button',null,'primary');b.id='publish-intake';b.addEventListener('click',()=>void publishIntake());root.append(b,el('p','','muted'));publishLabel();math(root);}
async function publishIntake(){const drafts=reviewJob.drafts.filter(d=>selectedDrafts.has(d.key)&&draftReady(d));if(!drafts.length)return;$('publish-intake').disabled=true;try{const result=await api('question-bank/intakes/'+reviewJob.id+'/publish',{method:'POST',body:{requestId:publishRequest,drafts}});message('intake-message',`已入库 ${result.added} 道，跳过重复 ${result.duplicates} 道。`);const status=await api('question-bank');$('catalog-count').textContent=status.count.toLocaleString();await openReview(reviewJob.id);void loadIntakes();}catch(e){message('intake-message',e.message,true);publishLabel();}}
async function mine(){const seq=++epoch;message('mine-message','正在读取…');try{const [rows,jobs]=await Promise.all([api('question-bank/uploads/mine?page='+minePage),api('question-bank/intakes')]);if(seq!==epoch)return;$('my-intakes').replaceChildren(...(minePage===1?jobs.map(jobRow):[]));$('my-questions').replaceChildren();for(const row of rows){const item=card(row.question),b=el('button',row.active?'撤回共享':'恢复共享','secondary');b.addEventListener('click',async()=>{b.disabled=true;try{await api('question-bank/uploads/'+row.id,{method:'PUT',body:{active:!row.active}});await mine();const status=await api('question-bank');$('catalog-count').textContent=status.count.toLocaleString();}catch(e){message('mine-message',e.message,true);b.disabled=false;}});item.append(el('p',row.active?'正在共享':'已撤回，仅自己可管理','muted'),b);$('my-questions').append(item);}message('mine-message',rows.length||jobs.length?'':'还没有上传记录。');$('mine-page').textContent=String(minePage);$('mine-prev').disabled=minePage===1;$('mine-next').disabled=rows.length<25;math($('my-questions'));}catch(e){if(seq===epoch)message('mine-message',e.message,true);}}
$('reload-mine').addEventListener('click',()=>void mine());$('mine-prev').addEventListener('click',()=>{if(minePage>1){minePage--;void mine();}});$('mine-next').addEventListener('click',()=>{minePage++;void mine();});
$('learner-open').addEventListener('click',async()=>{if(!$('topic').value.trim()){message('search-message','先输入要推荐的知识点，再选择学员。',true);$('topic').focus();return;}selectedLearners.clear();$('learner-list').replaceChildren();$('learner-group').replaceChildren();$('learner-query').value='';$('learner-submit').disabled=true;$('learner-dialog').showModal();$('learner-topic').textContent='按“'+$('topic').value.trim()+'”为所选学员查找同类题';message('learner-message','正在加载班级…');const seq=++learnerEpoch;try{learnerGroups=await api('question-bank/learner-groups');if(seq!==learnerEpoch)return;$('learner-group').replaceChildren();for(const [i,g]of learnerGroups.entries()){const option=el('option',g.className+' · '+g.courseName);option.value=String(i);$('learner-group').append(option);}if(!learnerGroups.length){$('learner-list').replaceChildren();message('learner-message','当前账号暂无可用的任课班级，请先在小程序中维护任课关系。');$('learner-submit').disabled=true;return;}await loadLearners();}catch(e){message('learner-message',e.message,true);}});
$('learner-dialog').addEventListener('cancel',()=>learnerEpoch++);
$('learner-close').addEventListener('click',()=>{learnerEpoch++;$('learner-dialog').close();});$('learner-group').addEventListener('change',()=>void loadLearners());let learnerTimer;$('learner-query').addEventListener('input',()=>{clearTimeout(learnerTimer);learnerEpoch++;learnerTimer=setTimeout(()=>void loadLearners(),180);});
async function loadLearners(){const seq=++learnerEpoch,g=learnerGroups[Number($('learner-group').value)];if(!g)return;selectedLearners.clear();$('learner-submit').disabled=true;message('learner-message','正在读取学员…');try{const learners=await api('question-bank/learners?'+new URLSearchParams({classId:g.classId,courseId:g.courseId,query:$('learner-query').value.trim()}));if(seq!==learnerEpoch)return;$('learner-list').replaceChildren();for(const learner of learners){const label=el('label',null,'learner-option'),check=el('input');check.type='checkbox';check.addEventListener('change',()=>{if(check.checked&&selectedLearners.size>=10){check.checked=false;message('learner-message','一次最多选择10名学员',true);return;}check.checked?selectedLearners.add(learner.id):selectedLearners.delete(learner.id);$('learner-submit').disabled=!selectedLearners.size;message('learner-message',`已选 ${selectedLearners.size} 名学员`);});label.append(check,el('span',learner.name));$('learner-list').append(label);}message('learner-message',learners.length?'请选择学员（最多10名）':'没有匹配的学员。');}catch(e){if(seq===learnerEpoch)message('learner-message',e.message,true);}}
$('learner-submit').addEventListener('click',async()=>{const g=learnerGroups[Number($('learner-group').value)];if(!g||!selectedLearners.size)return;const request=++epoch;$('learner-submit').disabled=true;message('learner-message','正在查找同类题…');try{const result=await api('question-bank/learners/recommend',{method:'POST',body:{classId:g.classId,courseId:g.courseId,studentIds:[...selectedLearners],subject:$('search-subject').value,stage:$('search-stage').value,type:$('search-type').value,topic:$('topic').value.trim()}});if(request!==epoch)return;$('results').replaceChildren();for(const group of result.groups){const section=el('section',null,'learner-result');section.append(el('h2',group.studentName+' · '+group.returned+' 道推荐题'),el('p',group.message,'muted'),...group.matches.map(m=>card(m.question)));$('results').append(section);}math($('results'));$('next-batch').hidden=true;message('search-message',`已为 ${result.groups.length} 名学员查找同类题`);$('learner-dialog').close();}catch(e){message('learner-message',e.message,true);}finally{$('learner-submit').disabled=!selectedLearners.size;}});
void enter();
