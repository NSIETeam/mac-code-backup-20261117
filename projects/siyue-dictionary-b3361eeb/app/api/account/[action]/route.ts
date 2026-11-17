import {cookies} from 'next/headers';
import {eq} from 'drizzle-orm';
import {getDb} from '@/db';
import {employees} from '@/db/schema';
import {COOKIE,identity,getProfile,readBody,hashPassword,randomToken,equalHash,now,ApiError,issueSession,withSession,publicProfile,errorResponse,runtimeDb,sha256,requireEmployee,json} from '@/lib/server';
import {registerSchema,profileSchema} from '@/lib/validation';
type Context={params:Promise<{action:string}>};
export async function POST(request:Request,{params}:Context){try{
 const {action}=await params;const user=await identity();const p=await readBody(request);
 if(action==='register'){
  if(await getProfile(user.userId))throw new ApiError(409,'已完成登记，请使用工作密码登录。');
  const input=registerSchema.parse(p);const salt=randomToken();const time=now();
  const [profile]=await getDb().insert(employees).values({id:user.userId,name:input.name,roleIds:JSON.stringify(input.roleIds),department:input.department,campus:input.campus,grade:input.grade,passwordSalt:salt,passwordHash:await hashPassword(input.password,salt),createdAt:time,updatedAt:time}).onConflictDoNothing().returning();
  if(!profile)throw new ApiError(409,'已完成登记，请使用工作密码登录。');
  return withSession({profile:publicProfile(profile)},await issueSession(user.userId),201);
 }
 if(action==='login'){
  if(typeof p.password!=='string'||p.password.length>128)throw new ApiError(400,'请输入工作密码。');
  const profile=await getProfile(user.userId);if(!profile)throw new ApiError(409,'请先完成员工登记。');
  const db=runtimeDb();const current=Date.now();
  const attempt=await db.prepare('SELECT * FROM auth_attempts WHERE employee_id=?').bind(user.userId).first<{failures:number;window_start:number;locked_until:number}>();
  if(attempt&&attempt.locked_until>current)throw new ApiError(429,'尝试次数过多，请15分钟后再试。');
  if(!equalHash(await hashPassword(p.password,profile.passwordSalt),profile.passwordHash)){
   await db.prepare(`INSERT INTO auth_attempts(employee_id,failures,window_start,locked_until) VALUES (?,1,?,0)
    ON CONFLICT(employee_id) DO UPDATE SET failures=CASE WHEN window_start < ? THEN 1 ELSE failures+1 END,
    locked_until=CASE WHEN window_start >= ? AND failures+1>=5 THEN ? ELSE 0 END,
    window_start=CASE WHEN window_start < ? THEN ? ELSE window_start END`).bind(user.userId,current,current-900000,current-900000,current+900000,current-900000,current).run();
   throw new ApiError(401,'工作密码不正确，请重试。');
  }
  await db.prepare('DELETE FROM auth_attempts WHERE employee_id=?').bind(user.userId).run();
  return withSession({profile:publicProfile(profile)},await issueSession(user.userId));
 }
 if(action==='logout'){
  const token=(await cookies()).get(COOKIE)?.value;if(token)await runtimeDb().prepare('DELETE FROM work_sessions WHERE token_hash=? AND employee_id=?').bind(await sha256(token),user.userId).run();
  return withSession({ok:true},`${COOKIE}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0${process.env.NODE_ENV==='production'?'; Secure':''}`);
 }
 throw new ApiError(404,'操作不存在。');
}catch(e){return errorResponse(e);}}
export async function PATCH(request:Request,{params}:Context){try{
 if((await params).action!=='profile')throw new ApiError(404,'操作不存在。');
 const {user}=await requireEmployee();const p=profileSchema.parse(await readBody(request));
 const [profile]=await getDb().update(employees).set({name:p.name,roleIds:JSON.stringify(p.roleIds),department:p.department,campus:p.campus,grade:p.grade,updatedAt:now()}).where(eq(employees.id,user.userId)).returning();return json({profile:publicProfile(profile)});
}catch(e){return errorResponse(e);}}
