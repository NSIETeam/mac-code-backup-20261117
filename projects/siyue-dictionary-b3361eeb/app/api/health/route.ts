import {json,runtimeDb,errorResponse} from '@/lib/server';
export async function GET(){try{const db=runtimeDb();await db.batch([db.prepare('SELECT id FROM employees LIMIT 0'),db.prepare('SELECT id FROM needs LIMIT 0'),db.prepare('SELECT token_hash FROM work_sessions LIMIT 0')]);return json({status:'ok',storageReady:true});}catch(e){return errorResponse(e);}}
