import {and,eq} from 'drizzle-orm';
import {getDb} from '@/db';
import {needs} from '@/db/schema';
import {requireEmployee,readBody,now,json,errorResponse,ApiError} from '@/lib/server';
import {updateNeedSchema} from '@/lib/validation';
type Context={params:Promise<{id:string}>};
export async function PATCH(request:Request,{params}:Context){try{
 const {user}=await requireEmployee();const {id}=await params;const p=updateNeedSchema.parse(await readBody(request));
 const [row]=await getDb().update(needs).set({status:p.status,version:p.version+1,updatedAt:now()}).where(and(eq(needs.id,id),eq(needs.ownerId,user.userId),eq(needs.version,p.version))).returning();
 if(!row)throw new ApiError(409,'仅可更新本人需求；记录可能已变化，请刷新后重试。');
 const {requestId,...r}=row;return json({need:{...r,canEdit:true}});
}catch(e){return errorResponse(e);}}
export async function DELETE(request:Request,{params}:Context){try{
 const {user}=await requireEmployee();const {id}=await params;const p=await readBody(request);if(!Number.isInteger(p.version)||p.version<1)throw new ApiError(400,'记录版本不正确，请刷新。');
 const [row]=await getDb().delete(needs).where(and(eq(needs.id,id),eq(needs.ownerId,user.userId),eq(needs.version,p.version))).returning({id:needs.id});
 if(!row)throw new ApiError(409,'仅可删除本人需求；记录可能已变化，请刷新后重试。');return json({ok:true});
}catch(e){return errorResponse(e);}}
