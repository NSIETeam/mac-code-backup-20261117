import {api,run,confirm,prompt,token,Named,Workspace} from '../../services/api'
interface Binding {id:string;childName:string;relationship:string;studentId:string|null;state:string;revision:number}
interface PracticeQuestion {id:string;stem:string;material:string;answer:string;analysis:string;source:string;license:string}
interface Stage {practice?:PracticeQuestion[];id:string;title:string;from:string;to:string;problem:string;evidence:string;adjustment:string;result:string;actionState:string;nextReviewOn:string;state:string;revision:number}
const root='/academic-pilot';const today=()=>new Date(Date.now()+8*3600000).toISOString().slice(0,10)
Page({
 data:{allowed:false,busy:false,canReview:false,practiceCourses:[] as Named[],classId:'',students:[] as Named[],studentId:'',studentName:'',bindings:[] as Binding[],stages:[] as Stage[],editor:null as Stage|null,creating:false,title:'',from:today(),to:today(),actionLabels:['计划开展','正在进行','已复查'],actionStates:['planned','in_progress','reviewed'],actionIndex:0},owner:'',
 async onLoad(q:Record<string,string>){this.owner=token();this.setData({classId:q.classId||''});return run(async()=>{if(!this.data.classId)throw Error('请从班级的收作业入口进入');const me=await api<{canReview:boolean}>(root+'/me');const students=await api<Named[]>('/classes/'+this.data.classId+'/students');if(token()!==this.owner)return;this.setData({allowed:true,canReview:me.canReview,students});await this.refreshBindings();await this.choosePracticeCourse()})},
 onShow(){if(this.owner&&token()!==this.owner){this.setData({allowed:false,students:[],bindings:[],stages:[],editor:null});wx.navigateBack()}},
 async refreshBindings(){const bindings=await api<Binding[]>(root+'/parent-bindings?classId='+this.data.classId);if(this.owner===token())this.setData({bindings})},
 async choosePracticeCourse(){
  const workspace=await api<Workspace>('/workspace')
  if(this.owner!==token())return
  const ids=new Set(workspace.assignments.filter(a=>a.classId===this.data.classId).map(a=>a.courseId))
  this.setData({practiceCourses:workspace.courses.filter(c=>ids.has(c.id))})
 },
 practiceCourse(e:WechatMiniprogram.PickerChange){
  const course=this.data.practiceCourses[Number(e.detail.value)]
  if(course)return this.updatePractice(course.id)
  return undefined
 },
 clearPractice(){return this.updatePractice(null)},
 updatePractice(courseId:string|null){
  const editor=this.data.editor
  if(this.data.busy||!editor||editor.state!=='draft')return
  this.setData({busy:true})
  return run(async()=>{
   try{
    const result=await api<Stage>(root+'/stages/'+editor.id+'/practice','POST',{revision:editor.revision,courseId})
    if(this.owner!==token()||this.data.editor?.id!==editor.id)return
    this.setData({'editor.practice':result.practice||[],'editor.revision':result.revision})
   }finally{this.setData({busy:false})}
  })
 },
 refresh(){return run(()=>this.refreshBindings())},
 async child(e:WechatMiniprogram.PickerChange){if(this.data.busy)return;const student=this.data.students[Number(e.detail.value)];if(!student)return;this.setData({studentId:student.id,studentName:student.name,editor:null,stages:[],creating:false});return run(()=>this.refreshStages())},
 async refreshStages(){const id=this.data.studentId;const stages=await api<Stage[]>(root+'/students/'+id+'/stages');if(id===this.data.studentId&&token()===this.owner)this.setData({stages})},
 bind(e:WechatMiniprogram.PickerChange){if(this.data.busy)return;return run(async()=>{const binding=this.data.bindings.find(x=>x.id===e.currentTarget.dataset.id);const student=this.data.students[Number(e.detail.value)];if(!binding||!student)return;const checkCode=await prompt('请输入家长私聊提供的核对码','家长页面上的10位核对码');if(!checkCode||!await confirm('已在已有私聊中核实此申请者是 '+student.name+' 的监护人？确认后此微信可以查看孩子阶段反馈。','核对家长身份'))return;this.setData({busy:true});try{await api(root+'/parent-bindings/'+binding.id+'/approve','POST',{revision:binding.revision,studentId:student.id,confirmed:true,checkCode});await this.refreshBindings()}finally{this.setData({busy:false})}})},
 revoke(e:WechatMiniprogram.TouchEvent){return run(async()=>{const binding=this.data.bindings.find(x=>x.id===e.currentTarget.dataset.id);if(!binding||!await confirm('撤销后，该家长微信不能再查看此孩子的进展或以绑定身份交作业。'))return;await api(root+'/parent-bindings/'+binding.id+'/revoke','POST',{revision:binding.revision});await this.refreshBindings()})},
 add(){this.setData({creating:true,editor:null,title:'',from:today(),to:today()})},field(e:WechatMiniprogram.Input){const key=e.currentTarget.dataset.field;if(['title','from','to'].includes(key))this.setData({[key]:e.detail.value})},date(e:WechatMiniprogram.PickerChange){const key=e.currentTarget.dataset.field;if(['from','to'].includes(key))this.setData({[key]:String(e.detail.value)})},
 create(){if(this.data.busy)return;return run(async()=>{this.setData({busy:true});try{const editor=await api<Stage>(root+'/students/'+this.data.studentId+'/stages','POST',{title:this.data.title,from:this.data.from,to:this.data.to});this.setData({editor,creating:false,actionIndex:0})}finally{this.setData({busy:false})}})},
 edit(e:WechatMiniprogram.TouchEvent){const editor=this.data.stages.find(x=>x.id===e.currentTarget.dataset.id);if(editor)this.setData({editor:{...editor},creating:false,actionIndex:this.data.actionStates.indexOf(editor.actionState)})},
 text(e:WechatMiniprogram.Input){const field=e.currentTarget.dataset.field;if(['problem','evidence','adjustment','result'].includes(field))this.setData({['editor.'+field]:e.detail.value})},
 reviewDate(e:WechatMiniprogram.PickerChange){this.setData({'editor.nextReviewOn':String(e.detail.value)})},action(e:WechatMiniprogram.PickerChange){const index=Number(e.detail.value);this.setData({actionIndex:index,'editor.actionState':this.data.actionStates[index]})},
 save(){if(this.data.busy)return;return run(async()=>{const editor=this.data.editor;if(!editor)return;this.setData({busy:true});try{const saved=await api<Stage>(root+'/stages/'+editor.id,'PUT',editor);this.setData({editor:saved});await this.refreshStages();wx.showToast({title:'已存为待确认草稿',icon:'none'})}finally{this.setData({busy:false})}})},
 publish(){if(this.data.busy)return;return run(async()=>{const editor=this.data.editor;if(!editor||!await confirm('保存当前修改并确认让已核对身份的家长看到这些内容？','教务确认公开给家长'))return;this.setData({busy:true});try{const saved=await api<Stage>(root+'/stages/'+editor.id,'PUT',editor);this.setData({editor:saved});const published=await api<Stage>(root+'/stages/'+saved.id+'/publish','POST',{revision:saved.revision,confirmed:true});this.setData({editor:published});await this.refreshStages()}finally{this.setData({busy:false})}})},
 withdraw(){return run(async()=>{const editor=this.data.editor;if(!editor||!await confirm('撤回后，家长暂时看不到这一阶段反馈。'))return;await api(root+'/stages/'+editor.id+'/withdraw','POST',{revision:editor.revision});this.setData({editor:null});await this.refreshStages()})},back(){this.setData({editor:null,creating:false});if(this.data.studentId)return run(()=>this.refreshStages());return undefined}
})
