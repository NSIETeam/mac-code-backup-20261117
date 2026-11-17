import {createHash} from 'node:crypto';
export class PolicyReviewError extends Error {constructor(status,message){super(message);this.status=status;}}
const fail=(status,message)=>{throw new PolicyReviewError(status,message);};
export function createPolicyReviewStore(db,{getCatalog,requireActive}) {
 db.exec(`CREATE TABLE IF NOT EXISTS policy_reviewers(employee_id TEXT PRIMARY KEY REFERENCES employees(id),granted_at TEXT NOT NULL);
 CREATE TABLE IF NOT EXISTS policy_review_history(
 policy_id TEXT NOT NULL,version INTEGER NOT NULL,content_hash TEXT NOT NULL,snapshot_json TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('approved','changes_requested','needs_verification')),
 comment TEXT NOT NULL,reviewer_id TEXT NOT NULL REFERENCES employees(id),reviewer_name TEXT NOT NULL,created_at TEXT NOT NULL,
 PRIMARY KEY(policy_id,version));`);
 const allowed=id=>Boolean(db.prepare('SELECT 1 FROM policy_reviewers WHERE employee_id=?').get(id));
 function authorize(id){requireActive(id);if(!allowed(id))fail(403,'仅指定制度审核员可以使用制度审核页。');}
 function content(id){const c=getCatalog(),policy=c.policies.find(p=>p.id===id);if(!policy)fail(404,'制度不存在。');
  const refs=new Set(policy.sections.flatMap(s=>s.refs));
  const value={policy,document:c.policyDocuments.find(d=>d.id===policy.documentId)??null,sources:c.policySources.filter(s=>refs.has(s.id))};
  return {...value,contentHash:createHash('sha256').update(JSON.stringify(value)).digest('hex')};}
 function summary(row){return row?{version:row.version,status:row.status,comment:row.comment,reviewerName:row.reviewer_name,createdAt:row.created_at,contentHash:row.content_hash}:null;}
 const latest=id=>db.prepare('SELECT * FROM policy_review_history WHERE policy_id=? ORDER BY version DESC LIMIT 1').get(id);
 function list(employeeId){authorize(employeeId);return {items:getCatalog().policies.map(p=>{const c=content(p.id),r=summary(latest(p.id));return {id:p.id,title:p.title,category:p.category,sourceStatus:p.status,review:r,state:!r?'pending':r.contentHash!==c.contentHash?'stale':r.status};})};}
 function detail(employeeId,id){authorize(employeeId);const c=content(id),r=summary(latest(id));return {...c,review:r,state:!r?'pending':r.contentHash!==c.contentHash?'stale':r.status,history:db.prepare('SELECT * FROM policy_review_history WHERE policy_id=? ORDER BY version DESC LIMIT 50').all(id).map(summary),historyLimit:50};}
 function save(employee,id,input){authorize(employee.id);
  if(!input||typeof input!=='object'||!['approved','changes_requested','needs_verification'].includes(input.status)||typeof input.comment!=='string'||input.comment.trim().length>4000||!Number.isSafeInteger(input.version)||input.version<0||typeof input.contentHash!=='string')fail(400,'审核参数不完整或意见超过4000字。');
  const comment=input.comment.trim();if(input.status!=='approved'&&!comment)fail(400,'请填写需要修改或核实的具体内容。');
  db.exec('BEGIN IMMEDIATE');
  try{const c=content(id),previous=latest(id);
   if(c.contentHash!==input.contentHash)fail(409,'制度内容已更新，请重新读取后审核。');
   if((previous?.version??0)!==input.version)fail(409,'审核记录已更新，请刷新后再提交。');
   db.prepare('INSERT INTO policy_review_history VALUES(?,?,?,?,?,?,?,?,?)').run(id,input.version+1,c.contentHash,JSON.stringify(c),input.status,comment,employee.id,employee.name,new Date().toISOString());
   db.exec('COMMIT');
  }catch(e){db.exec('ROLLBACK');throw e;}
  return detail(employee.id,id);
 }
 return {allowed,list,detail,save};
}
