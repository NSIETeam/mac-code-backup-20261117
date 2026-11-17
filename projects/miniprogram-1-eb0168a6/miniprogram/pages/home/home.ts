import {api,run,user,Workspace,Named,Lecture,Handout,confirm,stateLabels,token,chooseNamedFile,upload} from '../../services/api'
import {rememberClassroom} from '../../services/classroom-context'
import {SessionSummary} from '../../services/class-sessions'
type ClassRow=Named&{ownerStaffId?:string;canDelete?:boolean;campusName?:string}
type CourseRow=Named&{canManage?:boolean}
type Lesson=Lecture&{canManage:boolean;handoutId:string;state:string;stateName:string;sessions:SessionSummary[]}
interface Detail {schoolClass:ClassRow;campusName:string;courses:CourseRow[];students:Named[];lectures:Lecture[];handouts:Handout[]}
interface Material {id:string;courseId:string;fileName:string;title:string}
Page({
 data:{classId:'',className:'',campusName:'',classes:[] as ClassRow[],visibleClasses:[] as ClassRow[],campuses:[] as Named[],courses:[] as CourseRow[],groups:[] as {id:string;name:string;lessons:Lesson[]}[],students:[] as Named[],materials:[] as Material[],materialsFailed:false,beta:false,loading:true,failed:false,query:'',canLink:false,linkChoices:[] as CourseRow[],linking:false,busy:false,editing:false,editKind:'',editName:'',editSequence:'',campusIndex:0},
 workspace:null as Workspace|null,detail:null as Detail|null,lessons:[] as Lesson[],editTarget:null as ClassRow|Lecture|null,epoch:0,
 onLoad(q:Record<string,string>){this.setData({classId:q.classId||''})},
 onShow(){return this.load()},onUnload(){this.epoch++},onPullDownRefresh(){return this.load().finally(()=>wx.stopPullDownRefresh())},
 load(){if(!user()){wx.reLaunch({url:'/pages/login/login'});return Promise.resolve()}return run(async()=>{const epoch=++this.epoch,identity=token();this.setData({loading:true,failed:false});try{
  const w=await api<Workspace>('/workspace');if(epoch!==this.epoch||identity!==token())return;this.workspace=w;
  const classes=(w.classes as ClassRow[]).map(c=>({...c,canDelete:w.user.role==='admin'||c.ownerStaffId===w.user.id,campusName:w.campuses.find(p=>p.id===c.campusId)?.name||''}));
  this.setData({classes,campuses:w.campuses});
  if(this.data.classId){
   const [d,pilot]=await Promise.all([api<Detail>('/classes/'+this.data.classId),api<{beta:boolean}>('/academic-pilot/me','GET',undefined,1200).catch(()=>({beta:false}))]);if(epoch!==this.epoch||identity!==token())return;this.setData({beta:pilot.beta===true});this.detail=d;
   const courses=d.courses.map(c=>({...c,canManage:(w.courses as CourseRow[]).find(x=>x.id===c.id)?.canManage}));
   this.setData({className:d.schoolClass.name,campusName:d.campusName,courses,students:d.students,canLink:w.user.role==='admin'||d.schoolClass.ownerStaffId===w.user.id,linkChoices:(w.courses as CourseRow[]).filter(c=>!courses.some(x=>x.id===c.id))});
   rememberClassroom({classId:this.data.classId,className:d.schoolClass.name});
   let sessions=await api<SessionSummary[]>('/class-sessions?classId='+encodeURIComponent(this.data.classId));
   const needsSync=d.handouts.some(h=>h.state==='published'&&!sessions.some(s=>d.handouts.some(other=>other.id===s.handoutId&&other.lectureId===h.lectureId)))||sessions.some(s=>!s.lessonDate&&!s.reviewed&&s.total<d.students.length);
   if(needsSync){await api('/classes/'+this.data.classId+'/sync-sessions','POST');sessions=await api<SessionSummary[]>('/class-sessions?classId='+encodeURIComponent(this.data.classId))}if(epoch!==this.epoch||identity!==token())return;
   this.lessons=d.lectures.map(l=>{const h=d.handouts.filter(h=>h.lectureId===l.id).sort((a,b)=>b.version-a.version)[0];return {...l,canManage:!!l.classId||!!courses.find(c=>c.id===l.courseId)?.canManage,handoutId:h?.id||'',state:h?.state||'',stateName:h?(stateLabels[h.state]||'待处理'):'待上传',sessions:sessions.filter(s=>d.handouts.some(h=>h.lectureId===l.id&&h.id===s.handoutId))}});
   void this.loadMaterials(epoch,identity);
  }
  this.filter();
 }catch(e){if(epoch===this.epoch)this.setData({failed:true});throw e}finally{if(epoch===this.epoch)this.setData({loading:false})}})},
 async loadMaterials(epoch:number,identity:string){const result=await Promise.allSettled(this.data.courses.map(c=>api<{materials:Material[]}>('/courses/'+c.id+'/materials')));if(epoch!==this.epoch||identity!==token())return;this.setData({materials:result.flatMap(r=>r.status==='fulfilled'?r.value.materials:[]),materialsFailed:result.some(r=>r.status==='rejected')})},
 filter(){const q=this.data.query.trim().toLowerCase();this.setData({visibleClasses:this.data.classes.filter(c=>(c.name+' '+c.campusName).toLowerCase().includes(q)),groups:this.data.courses.map(c=>({id:c.id,name:c.name,lessons:this.lessons.filter((l:Lesson)=>l.courseId===c.id&&('第'+l.sequence+'讲 '+l.title).toLowerCase().includes(q)).sort((a:Lesson,b:Lesson)=>a.sequence-b.sequence)}))})},
 search(e:WechatMiniprogram.Input){this.setData({query:e.detail.value});this.filter()},
 openClass(e:WechatMiniprogram.TouchEvent){wx.navigateTo({url:'/pages/home/home?classId='+encodeURIComponent(e.currentTarget.dataset.id)})},
 switchClass(){wx.reLaunch({url:'/pages/home/home'})},
 addClass(){wx.navigateTo({url:'/pages/quick-add/quick-add?kind=class'})},
 addLecture(){wx.navigateTo({url:'/pages/quick-add/quick-add?kind=lecture&classId='+this.data.classId})},
 addStudents(){wx.navigateTo({url:'/pages/quick-add/quick-add?kind=student&classId='+this.data.classId})},
 tools(){wx.navigateTo({url:'/pages/roll-call/roll-call?classId='+this.data.classId})},
 followUps(){wx.navigateTo({url:'/pages/student-follow-up/student-follow-up?classId='+this.data.classId})},
 todos(){if(this.data.beta)wx.navigateTo({url:'/pages/teacher-todos/teacher-todos?classId='+this.data.classId})},
 homework(){if(!this.data.beta)return;wx.navigateTo({url:'/pages/homework-collect/homework-collect?classId='+this.data.classId})},
 async upload(){const courses=this.data.courses;if(!courses.length){wx.showToast({title:'请先绑定课程',icon:'none'});return}let index=0;if(courses.length>1)index=await new Promise<number>(resolve=>wx.showActionSheet({itemList:courses.map(c=>c.name),success:r=>resolve(r.tapIndex),fail:()=>resolve(-1)}));if(index>=0)wx.navigateTo({url:'/pages/book-import/book-import?classId='+this.data.classId+'&courseId='+courses[index].id})},
 link(){if(this.data.canLink)this.setData({linking:!this.data.linking})},
 linkCourse(e:WechatMiniprogram.TouchEvent){if(this.data.busy)return;return run(async()=>{this.setData({busy:true});try{await api('/classes/'+this.data.classId+'/courses','POST',{courseId:e.currentTarget.dataset.id});this.setData({linking:false});await this.load()}finally{this.setData({busy:false})}})},
 openLesson(e:WechatMiniprogram.TouchEvent){const l=this.lessons.find((l:Lesson)=>l.id===e.currentTarget.dataset.id);if(!l)return;if(!l.handoutId){return this.uploadLesson(l)}if(l.state==='awaiting-approval'){wx.showToast({title:'讲义等待中央教研室审核',icon:'none'});return}if(l.state==='draft'||l.state==='processing'||l.state==='queued'||l.state==='failed'){wx.navigateTo({url:'/pages/review/review?id='+l.handoutId+'&classId='+this.data.classId+'&courseId='+l.courseId});return}if(l.sessions.length){wx.navigateTo({url:'/pages/session-mark/session-mark?id='+l.sessions[0].id});return}wx.navigateTo({url:'/pages/session-setup/session-setup?classId='+this.data.classId+'&lectureId='+l.id})},
 uploadLesson(l:Lesson){if(!l.canManage||this.data.busy)return;return run(async()=>{const file=await chooseNamedFile(['doc','docx','pdf']);if(!file)return;this.setData({busy:true});try{const h=await upload<{id:string}>('/lectures/'+l.id+'/handouts',file.path);wx.navigateTo({url:'/pages/review/review?id='+h.id+'&classId='+this.data.classId+'&courseId='+l.courseId})}finally{this.setData({busy:false})}})},
 openSession(e:WechatMiniprogram.TouchEvent){wx.navigateTo({url:'/pages/session-mark/session-mark?id='+e.currentTarget.dataset.id})},
 material(e:WechatMiniprogram.TouchEvent){const m=this.data.materials.find(m=>m.id===e.currentTarget.dataset.id);if(m)wx.navigateTo({url:'/pages/material-edition/material-edition?id='+m.id+'&courseId='+m.courseId+'&sourceScope=course&fileName='+encodeURIComponent(m.fileName)})},
 editClass(e:WechatMiniprogram.TouchEvent){const c=this.data.classes.find(c=>c.id===(e.currentTarget.dataset.id||this.data.classId));if(!c)return;this.editTarget=c;this.setData({editing:true,editKind:'class',editName:c.name,campusIndex:Math.max(0,this.data.campuses.findIndex(x=>x.id===c.campusId))})},
 editLecture(e:WechatMiniprogram.TouchEvent){const l=this.lessons.find((l:Lesson)=>l.id===e.currentTarget.dataset.id);if(!l?.canManage)return;this.editTarget=l;this.setData({editing:true,editKind:'lecture',editName:l.title,editSequence:String(l.sequence)})},
 input(e:WechatMiniprogram.Input){const field=e.currentTarget.dataset.field;if(field==='editName'||field==='editSequence')this.setData({[field]:e.detail.value})},
 campus(e:WechatMiniprogram.PickerChange){this.setData({campusIndex:Number(e.detail.value)})},
 cancelEdit(){if(!this.data.busy)this.setData({editing:false})},
 saveEdit(){if(this.data.busy||!this.editTarget)return;return run(async()=>{const target=this.editTarget!;const name=this.data.editName.trim();if(!name)throw Error('请填写名称');this.setData({busy:true});try{if(this.data.editKind==='class'){const c=target as ClassRow;await api('/classes/'+c.id,'PUT',{name,campusId:this.data.campuses[this.data.campusIndex].id,expectedName:c.name,expectedCampusId:c.campusId})}else{const l=target as Lecture,sequence=Number(this.data.editSequence);if(!Number.isInteger(sequence)||sequence<1||sequence>10000)throw Error('讲次序号请填写 1–10000 的整数');await api('/lectures/'+l.id,'PUT',{title:name,sequence,expectedTitle:l.title,expectedSequence:l.sequence})}this.setData({editing:false});await this.load()}finally{this.setData({busy:false})}})},
 deleteClass(e:WechatMiniprogram.TouchEvent){const c=this.data.classes.find(c=>c.id===e.currentTarget.dataset.id);if(!c?.canDelete||this.data.busy)return;return run(async()=>{this.setData({busy:true});try{if(!await confirm('删除“'+c.name+'”？将从班级列表移除，历史讲义和学生记录保留。','删除班级'))return;await api('/classes/'+c.id+'?expectedName='+encodeURIComponent(c.name)+'&expectedCampusId='+c.campusId,'DELETE');await this.load()}finally{this.setData({busy:false})}})},
 deleteLecture(e:WechatMiniprogram.TouchEvent){const l=this.lessons.find((l:Lesson)=>l.id===e.currentTarget.dataset.id);if(!l?.canManage||this.data.busy)return;return run(async()=>{this.setData({busy:true});try{if(!await confirm('删除第'+l.sequence+'讲“'+l.title+'”？'+(!l.classId?'这是共享课程，会影响所有关联班级。':'')+'历史原稿和记录保留。','删除讲次'))return;await api('/lectures/'+l.id+'?expectedTitle='+encodeURIComponent(l.title)+'&expectedSequence='+l.sequence,'DELETE');await this.load()}finally{this.setData({busy:false})}})}
})
