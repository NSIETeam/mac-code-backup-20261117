export interface ClassSession { id:string; classId:string; courseId:string; handoutId:string; lessonDate:string }
export interface SessionSummary extends ClassSession { className:string; title:string; sequence:number; total:number; reviewed:number }
export interface SessionMark { studentId:string; revision:number; reviewed:boolean; questionIds:string[]; notes:Record<string,string> }
export interface SessionQuestion { id:string; label:string; order:number; previews:string[]; text:string }
export interface SessionDetail { session:ClassSession; className:string; lecture:{id:string;sequence:number;title:string}; students:{id:string;name:string;code:string}[]; questions:SessionQuestion[]; marks:SessionMark[] }
export function draftKey(userId:string,sessionId:string,studentId:string){return 'siyue_class_draft:'+userId+':'+sessionId+':'+studentId}
export function markPayload(mark:SessionMark){return {revision:mark.revision,questionIds:mark.questionIds.slice().sort(),notes:{...mark.notes}}}
