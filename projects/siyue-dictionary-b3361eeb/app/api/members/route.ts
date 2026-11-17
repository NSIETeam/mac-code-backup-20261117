import {asc} from 'drizzle-orm';
import {getDb} from '@/db';
import {employees} from '@/db/schema';
import {requireEmployee,json,errorResponse} from '@/lib/server';
export async function GET(){try{const {user}=await requireEmployee();const rows=await getDb().select({id:employees.id,name:employees.name,roleIds:employees.roleIds,department:employees.department,campus:employees.campus,grade:employees.grade,createdAt:employees.createdAt}).from(employees).orderBy(asc(employees.name));return json({members:rows.map(p=>({...p,roleIds:JSON.parse(p.roleIds),isSelf:p.id===user.userId}))});}catch(e){return errorResponse(e);}}
