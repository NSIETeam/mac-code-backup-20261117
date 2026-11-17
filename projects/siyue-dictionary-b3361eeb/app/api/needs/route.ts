import {and,desc,eq} from 'drizzle-orm';
import {getDb} from '@/db';
import {needs} from '@/db/schema';
import {requireEmployee,readBody,now,json,errorResponse,ApiError} from '@/lib/server';
import {needSchema} from '@/lib/validation';
export async function GET(){try{const {user}=await requireEmployee();const rows=await getDb().select().from(needs).orderBy(desc(needs.createdAt));return json({needs:rows.map(({requestId,...r})=>({...r,canEdit:r.ownerId===user.userId})),total:rows.length});}catch(e){return errorResponse(e);}}
export async function POST(request:Request){try{
 const {user,profile}=await requireEmployee();const p=needSchema.parse(await readBody(request));const db=getDb();
 const condition=and(eq(needs.ownerId,user.userId),eq(needs.requestId,p.requestId));const old=(await db.select().from(needs).where(condition).limit(1))[0];
 if(old){
  if(Object.entries(p).some(([k,v])=>old[k as keyof typeof old]!==v))throw new ApiError(409,'上次提交已保存，但内容与本次不同。请关闭表单刷新查看，新增需求请重新打开登记表。');
  const {requestId,...r}=old;return json({need:{...r,canEdit:true}});
 }
 const timestamp=now();const [inserted]=await db.insert(needs).values({...p,id:crypto.randomUUID(),ownerId:user.userId,author:profile.name,status:'待核实',createdAt:timestamp,updatedAt:timestamp}).onConflictDoNothing().returning();
 const row=inserted||(await db.select().from(needs).where(condition).limit(1))[0];
 if(!inserted && Object.entries(p).some(([k,v])=>row[k as keyof typeof row]!==v))throw new ApiError(409,'上次提交已保存，但内容与本次不同。请刷新查看，新增需求请重新打开登记表。');
 const {requestId,...r}=row;return json({need:{...r,canEdit:true}},inserted?201:200);
}catch(e){return errorResponse(e);}}
