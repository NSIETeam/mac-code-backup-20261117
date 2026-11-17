export interface ClassroomContext { classId:string; className?:string; sessionId?:string; courseId?:string; handoutId?:string; lectureTitle?:string }
function key():string { const account=wx.getStorageSync('siyue_user'); return account && account.id ? 'siyue_classroom_context:'+account.id : '' }
export function currentClassroom():ClassroomContext|null { const k=key();if(!k)return null;const value=wx.getStorageSync(k);return value && typeof value.classId==='string' && value.classId ? value : null }
export function rememberClassroom(next:ClassroomContext):void { const k=key();if(!k||!next.classId)return;const previous=currentClassroom();wx.setStorageSync(k,{...(previous && previous.classId===next.classId?previous:{}),...next}) }
export function forgetClassroom():void { const k=key();if(k)wx.removeStorageSync(k) }
export function classroomUrl():string { const context=currentClassroom();return context?.sessionId?'/pages/session-mark/session-mark?id='+encodeURIComponent(context.sessionId):context?'/pages/home/home?classId='+encodeURIComponent(context.classId):'/pages/home/home' }
