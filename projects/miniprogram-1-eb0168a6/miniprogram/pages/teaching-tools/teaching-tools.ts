import {api,error,settings,token,user,confirm} from '../../services/api'
interface Choice{id:string;name:string}
interface Session{id:string;title:string;sequence:number;lessonDate:string}
interface Question{id:string;label:string;pages:number;wrong:number}
interface Report{id:string;classId:string;className:string;title:string;lessonDate:string;sequence:number;reviewed:number;total:number;studentsWithErrors:number;questions:Question[];generalPlan:string[];hasUnresolvedQuestions:boolean}
interface Plan{questionId:string;topic:string;diagnose:string;teach:string;check:string;label?:string}
interface Exercise{question:string;answer:string;explanation:string}
interface Lesson{board:string;handout:string;basic:Exercise[];advanced:Exercise[]}
interface Correction{original:string;suggested:string;reason:string;applied:boolean}
interface EssayReview{corrections:Correction[];feedback:string;basicExample:string;advancedExample:string;originalText:string;revisedText:string;wordCount:number;reviewWarnings:string[]}
interface EssayResult{essays:EssayReview[];sharedIssues:string[]}
type Mode='analysis'|'lesson'|'essays'
interface Job{id:string;classId:string;kind:Mode;state:string;error:string;input:any;output:any;reviewedAt:string|null;createdAt:string;kindLabel?:string;timeLabel?:string}
const prefix='/teaching-tools'
const emptyJobs=()=>({analysis:null as Job|null,lesson:null as Job|null,essays:null as Job|null})
// Stable UUIDs survive an interrupted enqueue; a retry of the same request never creates a second task.
function uuid(){return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,c=>{const n=Math.floor(Math.random()*16);return (c==='x'?n:(n&3)|8).toString(16)})}
Page({
 data:{mode:'analysis' as Mode,classes:[] as Choice[],sessions:[] as Session[],classId:'',className:'',sessionId:'',report:null as Report|null,plan:[] as Plan[],
 unit:'',grammar:'',goal:'',prompt:'',rubric:'',essays:[''],lesson:null as Lesson|null,lessonUnit:'',reviews:null as EssayResult|null,
 lessonSnapshot:'',essaySnapshot:'',lessonJobId:'',lessonReviewed:false,lessonStale:false,reviewsStale:false,
 jobs:emptyJobs(),job:null as Job|null,history:[] as Job[],busy:false,message:'',requestId:'',requestSnapshot:''},
 _owner:'',_session:'',_active:true,_visible:true,_timer:null as ReturnType<typeof setTimeout>|null,
 current(){return this._active&&user()?.id===this._owner&&token()===this._session},
 key(){return 'siyue.teaching.v2.'+this._owner+'.'+this.data.classId},
 snapshot(mode:Mode):any{const d=this.data;return mode==='lesson'?{classId:d.classId,unit:d.unit,grammar:d.grammar,goal:d.goal}:mode==='essays'?{classId:d.classId,prompt:d.prompt,rubric:d.rubric,essays:d.essays.map(x=>x.trim())}:{sessionId:d.report?.id||d.sessionId}},
 refreshStale(){this.setData({lessonStale:!!this.data.lesson&&JSON.stringify(this.snapshot('lesson'))!==this.data.lessonSnapshot,reviewsStale:!!this.data.reviews&&JSON.stringify(this.snapshot('essays'))!==this.data.essaySnapshot})},
 save(){
  if(!this.current()||!this.data.classId)return
  const d=this.data
  try{wx.setStorageSync(this.key(),{version:2,owner:this._owner,classId:d.classId,mode:d.mode,unit:d.unit,grammar:d.grammar,goal:d.goal,prompt:d.prompt,rubric:d.rubric,essays:d.essays,sessionId:d.sessionId,
    lesson:d.lesson,lessonUnit:d.lessonUnit,reviews:d.reviews,lessonSnapshot:d.lessonSnapshot,essaySnapshot:d.essaySnapshot,lessonJobId:d.lessonJobId,lessonReviewed:d.lessonReviewed,jobs:d.jobs,requestId:d.requestId,requestSnapshot:d.requestSnapshot});wx.setStorageSync('siyue.teaching.last.'+this._owner,d.classId)}
  catch{this.setData({message:'本机草稿保存失败，请检查存储空间；已提交的生成任务仍在历史记录中'})}
 },
 restore(){
  const d=wx.getStorageSync(this.key())
  if(d&&d.version===2&&d.owner===this._owner&&d.classId===this.data.classId){
   const fields=['mode','unit','grammar','goal','prompt','rubric','essays','sessionId','lesson','lessonUnit','reviews','lessonSnapshot','essaySnapshot','lessonJobId','lessonReviewed','jobs','requestId','requestSnapshot']
   const patch:Record<string,any>={};fields.forEach(k=>{if(d[k]!==undefined)patch[k]=d[k]});this.setData(patch)
  }
  this.setData({job:this.data.jobs[this.data.mode]});this.refreshStale()
 },
 async onLoad(options:Record<string,string>){
  this._owner=user()?.id||'';this._session=token();this._active=true
  if(!this._owner||!this._session){wx.reLaunch({url:'/pages/login/login'});return}
  this.setData({busy:true})
  try{
   const classes=await api<Choice[]>(prefix+'/classes');if(!this.current())return;this.setData({classes})
   const last=wx.getStorageSync('siyue.teaching.last.'+this._owner);const row=classes.find(x=>x.id===last)
   if(row){this.setData({classId:row.id,className:row.name});this.restore();await this.loadClass()}
   if(options.id){const report=await api<Report>(prefix+'/sessions/'+encodeURIComponent(options.id));if(!this.current())return;const cl=classes.find(x=>x.id===report.classId);if(cl){this.save();this.clearClass();this.setData({classId:cl.id,className:cl.name});this.restore();await this.loadClass();this.setData({mode:'analysis',sessionId:report.id,report:this.sorted(report),job:this.data.jobs.analysis})}}
   this.poll()
  }catch(e){if(this.current())error(e)}finally{if(this.current())this.setData({busy:false})}
 },
 onShow(){this._visible=true;if(this._owner&&!this.current()){this.clearClass();this._active=false;wx.reLaunch({url:'/pages/login/login'});return}if(this._owner){this.poll();void this.loadHistory()}},
 onHide(){this._visible=false;this.save();this.stopPoll()},
 onUnload(){this._visible=false;this.save();this.stopPoll();this._active=false},
 stopPoll(){if(this._timer)clearTimeout(this._timer);this._timer=null},
 sorted(report:Report){return {...report,questions:[...report.questions].sort((a,b)=>b.wrong-a.wrong)}},
 clearClass(){this.stopPoll();this.setData({sessions:[],sessionId:'',report:null,plan:[],unit:'',grammar:'',goal:'',prompt:'',rubric:'',essays:[''],lesson:null,lessonUnit:'',reviews:null,lessonSnapshot:'',essaySnapshot:'',lessonJobId:'',lessonReviewed:false,lessonStale:false,reviewsStale:false,jobs:emptyJobs(),job:null,history:[],requestId:'',requestSnapshot:'',message:''})},
 async loadClass(){
  const id=this.data.classId;if(!id)return
  const sessions=await api<Session[]>(prefix+'/classes/'+id+'/sessions');if(!this.current()||id!==this.data.classId)return
  this.setData({sessions:sessions.map(x=>({...x,title:[x.lessonDate||'日期待补充','第'+x.sequence+'讲',x.title].join(' · ')}))})
  if(this.data.sessionId&&sessions.some(x=>x.id===this.data.sessionId)){
   const report=await api<Report>(prefix+'/sessions/'+this.data.sessionId);if(this.current()&&id===this.data.classId)this.setData({report:this.sorted(report)})
  }
  await this.loadHistory()
 },
 async updateDate(e:WechatMiniprogram.PickerChange){const report=this.data.report;if(!report||this.data.busy||!this.current())return;this.setData({busy:true});try{await api(prefix+'/sessions/'+report.id+'/date','PUT',{lessonDate:e.detail.value,expectedDate:report.lessonDate});if(this.current()&&this.data.report?.id===report.id){this.setData({report:{...report,lessonDate:String(e.detail.value)}});await this.loadClass()}}catch(e){if(this.current())error(e)}finally{if(this.current())this.setData({busy:false})}},
 async loadHistory(){const id=this.data.classId;if(!id||!this.current())return;try{const rows=await api<Job[]>(prefix+'/jobs?classId='+id);if(this.current()&&id===this.data.classId)this.setData({history:rows.slice(0,20).map(x=>({...x,kindLabel:({analysis:'错题讲评',lesson:'英语备课',essays:'作文批改'})[x.kind],timeLabel:x.createdAt.replace('T',' ').slice(0,16)}))})}catch{/* Keep current saved work usable while offline. */}},
 mode(e:WechatMiniprogram.TouchEvent){if(this.data.busy)return;const mode=e.currentTarget.dataset.mode as Mode;if(!['analysis','lesson','essays'].includes(mode))return;this.setData({mode,job:this.data.jobs[mode],message:''});this.save();this.poll()},
 async pickClass(e:WechatMiniprogram.PickerChange){const row=this.data.classes[Number(e.detail.value)];if(!row||this.data.busy)return;this.save();this.clearClass();this.setData({classId:row.id,className:row.name,busy:true});this.restore();try{await this.loadClass();this.poll()}catch(e){if(this.current())error(e)}finally{if(this.current()){this.setData({busy:false});this.save()}}},
 async pickSession(e:WechatMiniprogram.PickerChange){const row=this.data.sessions[Number(e.detail.value)];if(!row||this.data.busy)return;this.setData({sessionId:row.id,report:null,plan:[],busy:true,message:''});try{const report=await api<Report>(prefix+'/sessions/'+row.id);if(this.current())this.setData({report:this.sorted(report)})}catch(e){if(this.current())error(e)}finally{if(this.current()){this.setData({busy:false});this.save()}}},
 field(e:WechatMiniprogram.Input){const key=String(e.currentTarget.dataset.key);if(['unit','grammar','goal','prompt','rubric'].includes(key)){this.setData({[key]:e.detail.value});this.refreshStale();this.save()}},
 essay(e:WechatMiniprogram.Input){const i=Number(e.currentTarget.dataset.index);if(i>=0&&i<this.data.essays.length){this.setData({['essays['+i+']']:e.detail.value});this.refreshStale();this.save()}},
 addEssay(){if(this.data.essays.length<3){this.setData({essays:[...this.data.essays,'']});this.refreshStale();this.save()}},
 removeEssay(e:WechatMiniprogram.TouchEvent){const i=Number(e.currentTarget.dataset.index);if(this.data.essays.length>1){this.setData({essays:this.data.essays.filter((_,j)=>j!==i)});this.refreshStale();this.save()}},
 draftPlan(){return this.generate()},
 async generate(){
  if(this.data.busy||!this.data.classId||!this.current())return
  const kind=this.data.mode;const previous=this.data.jobs[kind];if(previous&&['queued','running'].includes(previous.state)){this.setData({message:'该工具已有任务正在生成，可离开页面后回来查看'});return}
  const input=this.snapshot(kind)
  if(kind==='lesson'&&(!input.unit.trim()||!input.grammar.trim()||!input.goal.trim())||kind==='essays'&&(!input.prompt.trim()||!input.rubric.trim()||input.essays.some((s:string)=>!s))||kind==='analysis'&&!input.sessionId){this.setData({message:'请完整填写本次输入；空作文请填写或移除'});return}
  const signature=kind+JSON.stringify(input);const id=this.data.requestSnapshot===signature&&this.data.requestId?this.data.requestId:uuid()
  this.setData({requestId:id,requestSnapshot:signature,busy:true,message:''});this.save()
  try{
   const job=await api<Job>(prefix+'/jobs','POST',{classId:this.data.classId,kind,requestId:id,input});if(!this.current())return
   this.setData({jobs:{...this.data.jobs,[kind]:job},job,requestId:'',requestSnapshot:''});this.save();this.poll();void this.loadHistory()
  }catch(e){if(this.current())this.setData({message:(e instanceof Error?e.message:'提交失败')+'；原有成果已保留，可重试'})}
  finally{if(this.current())this.setData({busy:false})}
 },
 applyJob(job:Job){
  const jobs={...this.data.jobs,[job.kind]:job};this.setData({jobs,job:job.kind===this.data.mode?job:this.data.job})
  if(job.state==='succeeded'&&job.output){
   if(job.kind==='lesson')this.setData({lesson:job.output,lessonUnit:job.input.unit,lessonSnapshot:JSON.stringify(job.input),lessonJobId:job.id,lessonReviewed:!!job.reviewedAt})
   else if(job.kind==='essays')this.setData({reviews:job.output,essaySnapshot:JSON.stringify(job.input)})
   else if(job.input.sessionId===this.data.report?.id)this.setData({plan:job.output.items.map((p:Plan)=>({...p,label:this.data.report?.questions.find(q=>q.id===p.questionId)?.label}))})
  }
  this.refreshStale();this.save()
 },
 poll(){
  this.stopPoll();const job=this.data.jobs[this.data.mode];if(!job||!this.current()||!this._visible)return
  const pollOnce=async()=>{
   if(!this.current()||!this._visible||this.data.jobs[this.data.mode]?.id!==job.id)return
   try{const fresh=await api<Job>(prefix+'/jobs/'+job.id);if(!this.current()||!this._visible||this.data.jobs[this.data.mode]?.id!==job.id)return;this.applyJob(fresh)
    if(['queued','running'].includes(fresh.state))this._timer=setTimeout(pollOnce,2500);else void this.loadHistory()
   }catch{if(this.current()&&this._visible&&this.data.jobs[this.data.mode]?.id===job.id){this.setData({message:'暂时无法读取任务进度，联网后自动恢复；请勿重复提交'});this._timer=setTimeout(pollOnce,7000)}}
  };void pollOnce()
 },
 async cancelJob(){const job=this.data.job;if(!job||!this.current())return;try{const updated=await api<Job>(prefix+'/jobs/'+job.id+'/cancel','POST',{});if(this.current()){this.applyJob(updated);this.stopPoll()}}catch(e){if(this.current())error(e)}},
 async openHistory(e:WechatMiniprogram.TouchEvent){if(this.data.busy)return;this.setData({busy:true});try{
  const job=await api<Job>(prefix+'/jobs/'+encodeURIComponent(e.currentTarget.dataset.id));if(!this.current()||job.classId!==this.data.classId)return
  this.setData({mode:job.kind,message:''});const i=job.input
  if(job.kind==='lesson')this.setData({unit:i.unit,grammar:i.grammar,goal:i.goal})
  else if(job.kind==='essays')this.setData({prompt:i.prompt,rubric:i.rubric,essays:i.essays})
  else{const report=await api<Report>(prefix+'/sessions/'+i.sessionId);if(!this.current())return;this.setData({sessionId:report.id,report:this.sorted(report),plan:[]})}
  this.applyJob(job);this.poll()
 }catch(e){if(this.current())error(e)}finally{if(this.current())this.setData({busy:false})}},
 async reviewLesson(){if(this.data.lessonStale||!this.data.lessonJobId)return;if(!await confirm('请确认已核对题意、难度、答案和解析。确认后可导出不含答案的学生版。','确认教师复核'))return;if(!this.current())return;try{const job=await api<Job>(prefix+'/jobs/'+this.data.lessonJobId+'/review','POST',{reviewed:true});if(this.current()&&job.id===this.data.lessonJobId){this.setData({lessonReviewed:!!job.reviewedAt});this.save()}}catch(e){if(this.current())error(e)}},
 async openWord(e:WechatMiniprogram.TouchEvent){const {lessonJobId,lessonUnit,lessonStale,lessonReviewed}=this.data;if(!lessonJobId||lessonStale||this.data.busy||!this.current())return;const audience=e.currentTarget.dataset.audience==='student'?'student':'teacher';if(audience==='student'&&!lessonReviewed){this.setData({message:'请先完成教师复核'});return}this.setData({busy:true});
  try{const url=settings.baseUrl+'/api'+prefix+'/jobs/'+lessonJobId+'/word?audience='+audience;
   const temp=await new Promise<string>((resolve,reject)=>wx.downloadFile({url,header:{Authorization:'Bearer '+token()},success:r=>r.statusCode===200?resolve(r.tempFilePath):reject(new Error('下载失败，请刷新任务状态后重试')),fail:()=>reject(new Error('下载中断，请重试'))}));if(!this.current())return
   const name=(lessonUnit||'英语备课').replace(/[\\/:*?"<>|]/g,'_').slice(0,35)+'-'+(audience==='student'?'学生版':'教师版')+'-'+lessonJobId.slice(0,8)+'.docx';const path=wx.env.USER_DATA_PATH+'/'+name
   await new Promise<void>((resolve,reject)=>wx.getFileSystemManager().copyFile({srcPath:temp,destPath:path,success:()=>resolve(),fail:()=>reject(new Error('保存失败，请检查手机存储空间'))}));if(!this.current())return
   await new Promise<void>((resolve,reject)=>wx.openDocument({filePath:path,fileType:'docx',showMenu:true,success:()=>resolve(),fail:()=>reject(new Error('无法打开Word，请使用手机Word或WPS重试'))}))
  }catch(e){if(this.current())error(e)}finally{if(this.current())this.setData({busy:false})}
 },
 async preview(e:WechatMiniprogram.TouchEvent){const report=this.data.report,q=report?.questions.find(x=>x.id===e.currentTarget.dataset.id);if(!report||!q?.pages||this.data.busy)return;this.setData({busy:true});try{const urls:string[]=[];for(let i=1;i<=q.pages;i++){urls.push(await new Promise<string>((resolve,reject)=>wx.downloadFile({url:settings.baseUrl+'/api'+prefix+'/sessions/'+report.id+'/questions/'+q.id+'/'+i,header:{Authorization:'Bearer '+token()},success:r=>r.statusCode===200?resolve(r.tempFilePath):reject(new Error('原题暂不可用')),fail:()=>reject(new Error('原题下载失败'))})));if(!this.current())return}wx.previewImage({urls,current:urls[0]})}catch(e){if(this.current())error(e)}finally{if(this.current())this.setData({busy:false})}},
 copyBoard(){if(this.current()&&this.data.lesson&&!this.data.lessonStale)wx.setClipboardData({data:this.data.lesson.board})},
 copyHandout(){const l=this.data.lesson;if(!this.current()||!l||this.data.lessonStale||!this.data.lessonReviewed)return;const exercise=(title:string,items:Exercise[])=>title+'\n'+items.map((q,i)=>`${i+1}. ${q.question}\n________________________`).join('\n');wx.setClipboardData({data:[l.handout,exercise('基础练习',l.basic),exercise('提升练习',l.advanced)].join('\n\n')})},
})
