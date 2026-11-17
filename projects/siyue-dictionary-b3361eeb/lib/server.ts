import {env} from 'cloudflare:workers';
import {cookies} from 'next/headers';
import {getChatGPTUser} from '@/app/chatgpt-auth';
import {getDb} from '@/db';
import {employees,workSessions} from '@/db/schema';
import {and,eq,gt} from 'drizzle-orm';
import {z} from 'zod';
export class ApiError extends Error{constructor(public status:number,message:string){super(message);}}
export const COOKIE='siyue_work_session';
export const now=()=>new Date().toISOString();
export function runtimeDb(){if(!env.DB)throw new ApiError(503,'数据服务暂时不可用，请稍后重试。');return env.DB;}
export function json(data:unknown,status=200){return Response.json(data,{status,headers:{'Cache-Control':'no-store'}});}
export function errorResponse(e:unknown){
 if(e instanceof ApiError)return json({error:e.message},e.status);
 if(e instanceof z.ZodError)return json({error:e.issues[0]?.message||'请检查填写内容。'},400);
 if(e instanceof SyntaxError)return json({error:'提交内容格式不正确。'},400);
 console.error('request-failed',{kind:e instanceof Error?e.name:'UnknownError',reference:crypto.randomUUID()});return json({error:'暂时无法保存或读取，请稍后重试。'},500);
}
export async function readBody(request:Request){
 if(request.headers.get('sec-fetch-site')==='cross-site')throw new ApiError(403,'请从网站内发起操作。');
 const origin=request.headers.get('origin');if(origin&&origin!==new URL(request.url).origin)throw new ApiError(403,'请求来源不匹配，请刷新页面。');
 if(Number(request.headers.get('content-length')||0)>32768)throw new ApiError(413,'内容过长，请精简。');
 const text=await request.text();if(text.length>32768)throw new ApiError(413,'内容过长，请精简。');return JSON.parse(text||'{}');
}
export async function identity(){const u=await getChatGPTUser();if(!u)throw new ApiError(401,'请先登录后继续。');return u;}
export function publicProfile(p:typeof employees.$inferSelect){return{id:p.id,name:p.name,roleIds:JSON.parse(p.roleIds) as string[],department:p.department,campus:p.campus,grade:p.grade,createdAt:p.createdAt};}
const hex=(b:ArrayBuffer|Uint8Array)=>Array.from(b instanceof Uint8Array?b:new Uint8Array(b),x=>x.toString(16).padStart(2,'0')).join('');
export const randomToken=()=>hex(crypto.getRandomValues(new Uint8Array(32)));
export const sha256=async(s:string)=>hex(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(s)));
export async function hashPassword(password:string,salt:string){const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(password),'PBKDF2',false,['deriveBits']);return hex(await crypto.subtle.deriveBits({name:'PBKDF2',salt:new TextEncoder().encode(salt),iterations:100000,hash:'SHA-256'},key,256));}
export function equalHash(a:string,b:string){let mismatch=a.length^b.length;for(let i=0;i<Math.max(a.length,b.length);i++)mismatch|=(a.charCodeAt(i)||0)^(b.charCodeAt(i)||0);return mismatch===0;}
export async function getProfile(id:string){return(await getDb().select().from(employees).where(eq(employees.id,id)).limit(1))[0]||null;}
export async function hasWorkSession(id:string){const token=(await cookies()).get(COOKIE)?.value;if(!token||!/^[a-f0-9]{64}$/.test(token))return false;return(await getDb().select({id:workSessions.employeeId}).from(workSessions).where(and(eq(workSessions.tokenHash,await sha256(token)),eq(workSessions.employeeId,id),gt(workSessions.expiresAt,Date.now()))).limit(1)).length>0;}
export async function requireEmployee(){const user=await identity();if(!await hasWorkSession(user.userId))throw new ApiError(401,'请登录工作账号后继续。');const profile=await getProfile(user.userId);if(!profile)throw new ApiError(401,'请先完善员工资料。');return{user,profile};}
export async function issueSession(id:string){const token=randomToken();const db=runtimeDb();await db.batch([db.prepare('DELETE FROM work_sessions WHERE expires_at < ?').bind(Date.now()),db.prepare('INSERT INTO work_sessions (token_hash,employee_id,expires_at) VALUES (?,?,?)').bind(await sha256(token),id,Date.now()+604800000)]);return`${COOKIE}=${token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800${process.env.NODE_ENV==='production'?'; Secure':''}`;}
export function withSession(data:unknown,cookie:string,status=200){const res=json(data,status);res.headers.set('Set-Cookie',cookie);return res;}
