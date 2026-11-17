import {api,confirm,error,token,user} from '../../services/api'
import {SessionSummary} from '../../services/class-sessions'

interface RollStudent {studentId:string;name:string;status:string;revision:number;note:string;transferClassId?:string|null;transferClassName?:string|null;transferOn?:string|null}
interface ExceptionRecord {studentId:string;status:'leave'|'transfer';transferClassId?:string;transferOn?:string}
interface RollView {snapshot:string;students:RollStudent[];transferClasses:{id:string;name:string}[];incomingTransfers:{studentId:string;name:string;sourceClassName:string;sourceLessonDate:string;transferOn:string}[]}
interface StudentView extends RollStudent {label:string;exception:boolean;unresolved:boolean}
const root='/attendance'
Page({
 data:{loading:true,saving:false,allowed:false,error:'',className:'',sessions:[] as (SessionSummary&{label:string})[],sessionIndex:0,students:[] as StudentView[],incoming:[] as RollView['incomingTransfers'],transferClasses:[] as RollView['transferClasses'],exceptions:[] as ExceptionRecord[],dirty:false,exceptionCount:0,transferStudentId:'',transferStudentName:'',targetIndex:-1,transferOn:''},
 snapshot:'',openedToken:'',requestEpoch:0,
 async onLoad(q:Record<string,string>){
  const owner=token();this.openedToken=owner;
  this.setData({allowed:false,students:[],incoming:[],exceptions:[],error:''});
  try{
   if(!owner||!user())throw Error('请先登录');
   if(!q.classId)throw Error('请先选择班级');
   const sessions=(await api<SessionSummary[]>('/class-sessions?classId='+encodeURIComponent(q.classId))).filter(s=>s.classId===q.classId).map(s=>({...s,label:s.lessonDate+' · '+s.title}));
   if(owner!==token())return;
   this.setData({allowed:true,sessions,className:sessions[0]?.className||'',sessionIndex:0});
   if(sessions.length)await this.loadSession(0);else this.setData({error:'这个班级暂无课次，请先在“记录错题”中开课。'});
  }catch(e){this.setData({error:e instanceof Error?e.message:'点名暂时无法打开'})}finally{this.setData({loading:false})}
 },
 onShow(){if(this.openedToken&&this.openedToken!==token()){this.requestEpoch++;this.snapshot='';this.setData({allowed:false,className:'',sessions:[],students:[],incoming:[],exceptions:[],transferStudentId:'',transferStudentName:'',transferClasses:[],transferOn:'',dirty:false,error:'登录账号已变化，请返回班级重新进入。'});wx.disableAlertBeforeUnload()}},
 onUnload(){this.requestEpoch++},
 async loadSession(index:number){
  const session=this.data.sessions[index];if(!session)return;
  const epoch=++this.requestEpoch,owner=token();this.setData({loading:true});
  try{const view=await api<RollView>(root+'/sessions/'+session.id+'/roll-call');if(epoch!==this.requestEpoch||owner!==token())return;this.setData({sessionIndex:index,error:''});this.applyView(view)}finally{if(epoch===this.requestEpoch)this.setData({loading:false})}
 },
 applyView(view:RollView){
  this.snapshot=view.snapshot;
  const exceptions=view.students.filter(s=>s.status==='leave'||s.status==='transfer').map(s=>({studentId:s.studentId,status:s.status as 'leave'|'transfer',...(s.status==='transfer'?{transferClassId:s.transferClassId||'',transferOn:s.transferOn||''}:{})}));
  this.setData({students:view.students.map(s=>({...s,label:s.status==='leave'?'请假':s.status==='transfer'?'临时调班 · '+(s.transferClassName||'待核对')+' · '+s.transferOn:s.status==='late'?'原登记迟到 · 请核对':s.status==='absent'?'原登记缺勤 · 请核对':'',exception:['leave','transfer'].includes(s.status),unresolved:['late','absent'].includes(s.status)})),exceptions,exceptionCount:exceptions.length,transferClasses:view.transferClasses,incoming:view.incomingTransfers||[],dirty:false});wx.disableAlertBeforeUnload()
 },
 async changeSession(e:WechatMiniprogram.PickerChange){
  if(this.data.saving||this.data.loading)return;
  if(this.data.dirty&&!await confirm('放弃本次未保存的点名修改？'))return;
  try{await this.loadSession(Number(e.detail.value))}catch(e){error(e)}
 },
 async refresh(){if(this.data.loading||this.data.saving)return;if(this.data.dirty&&!await confirm('放弃本次未保存的点名修改并重新读取？'))return;try{await this.loadSession(this.data.sessionIndex)}catch(e){error(e)}},
 async selectStudent(e:WechatMiniprogram.TouchEvent){
  if(this.data.loading||this.data.saving)return;
  const student=this.data.students.find(s=>s.studentId===e.currentTarget.dataset.id);if(!student)return;
  const items=['请假','临时调班级'];if(student.exception||student.unresolved)items.push('恢复正常出勤');
  const choice=await new Promise<number>(resolve=>wx.showActionSheet({itemList:items,success:r=>resolve(r.tapIndex),fail:()=>resolve(-1)}));
  if(token()!==this.openedToken){this.onShow();return}
  if(choice===0)this.setException(student.studentId,{studentId:student.studentId,status:'leave'});
  else if(choice===1){
   if(!this.data.transferClasses.length){wx.showToast({title:'暂无可调入的班级',icon:'none'});return}
   this.setData({transferStudentId:student.studentId,transferStudentName:student.name,targetIndex:this.data.transferClasses.findIndex(c=>c.id===student.transferClassId),transferOn:student.transferOn||this.data.sessions[this.data.sessionIndex].lessonDate})
  }else if(choice===2)this.setException(student.studentId,null)
 },
 setException(id:string,value:ExceptionRecord|null){
  const exceptions=this.data.exceptions.filter(x=>x.studentId!==id);if(value)exceptions.push(value);
  const students=this.data.students.map(s=>s.studentId!==id?s:{...s,status:value?.status||'present',exception:!!value,unresolved:false,transferClassId:value?.transferClassId||null,transferOn:value?.transferOn||null,label:value?.status==='leave'?'请假':value?'临时调班 · '+(this.data.transferClasses.find(c=>c.id===value.transferClassId)?.name||'')+' · '+value.transferOn:''});
  this.setData({students,exceptions,exceptionCount:exceptions.length,dirty:true});wx.enableAlertBeforeUnload({message:'点名修改尚未保存'})
 },
 targetClass(e:WechatMiniprogram.PickerChange){this.setData({targetIndex:Number(e.detail.value)})},
 targetDate(e:WechatMiniprogram.PickerChange){this.setData({transferOn:String(e.detail.value)})},
 cancelTransfer(){this.setData({transferStudentId:''})},
 stopTap(){},
 saveTransfer(){const target=this.data.transferClasses[this.data.targetIndex];if(!target||!this.data.transferOn)return;this.setException(this.data.transferStudentId,{studentId:this.data.transferStudentId,status:'transfer',transferClassId:target.id,transferOn:this.data.transferOn});this.setData({transferStudentId:''})},
 async save(){
  if(this.data.saving||this.data.loading||!this.data.allowed||!this.data.students.length)return;
  if(this.data.students.some(s=>s.unresolved)){wx.showToast({title:'请先核对原迟到或缺勤记录',icon:'none'});return}
  if(token()!==this.openedToken){this.onShow();return}
  this.setData({saving:true});
  try{const s=this.data.sessions[this.data.sessionIndex],view=await api<RollView>(root+'/sessions/'+s.id+'/roll-call','PUT',{snapshot:this.snapshot,confirmed:true,exceptions:this.data.exceptions});if(token()!==this.openedToken)return;this.applyView(view);wx.showToast({title:'点名已保存',icon:'success'})}catch(e){error(e)}finally{this.setData({saving:false})}
 }
})
