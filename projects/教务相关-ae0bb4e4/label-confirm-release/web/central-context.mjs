const materialPages=new Set(['book','book-pages','handout','course','course-materials','preview','material-library','material-edition']);
const tabs=new Set(['problems','pending','courses']);
export function centralReturn(current){
 const p=current?.p||{};return {tab:tabs.has(p.centralTab)?p.centralTab:tabs.has(p.tab)?p.tab:'problems',...(p.centralCategory||p.category?{category:p.centralCategory||p.category}:{})};
}
export function centralNavigation(current,name,parameters={}){
 const p={...parameters},explicit=p.central==='0';
 if(explicit){delete p.central;delete p.centralTab;delete p.centralCategory;return {name,p};}
 const active=current?.p?.central==='1';
 if(active&&['class','setup','grade','courses','home'].includes(name))return {name:'central-research',p:name==='courses'?{tab:'courses'}:centralReturn(current)};
 if(materialPages.has(name)&&(active||p.central==='1'||current?.name==='central-research')){
  const back=centralReturn(current);
  return {name,p:{central:'1',centralTab:name==='course'&&current?.name==='central-research'?'courses':back.tab,...(back.category?{centralCategory:back.category}:{}),...p}};
 }
 return {name,p};
}
// Material-only scope is checked again by the server. Never attach it to class,
// roster, grades, accounts or print-job requests.
export function centralApiPath(path,current){
 if(current?.p?.central!=='1')return path;
 const base=path.split('?')[0];
 const allowed=/^\/(?:books\/[a-z0-9-]+(?:\/(?:page-preview|retry|confirm|page-split|publish-library))?|courses\/[a-z0-9-]+\/(?:books|lectures|materials(?:\/[a-z0-9-]+\/file)?)|lectures(?:\/[a-z0-9-]+\/handouts)?|handouts\/[a-z0-9-]+(?:\/(?:edit|verify-selected|confirm-all|publish|retry|regions(?:\/(?:history|undo|[a-z0-9-]+\/\d+))?))?|previews\/handout\/[a-z0-9-]+(?:\/file)?|print-files\/source\/(?:book|handout)\/[a-z0-9-]+)$/.test(base);
 if(!allowed)return path;
 const index=path.indexOf('?'),query=new URLSearchParams(index<0?'':path.slice(index+1));query.set('central','1');
 return base+'?'+query;
}
