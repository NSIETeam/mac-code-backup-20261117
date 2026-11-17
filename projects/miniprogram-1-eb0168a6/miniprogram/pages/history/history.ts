import { api, run, Named, Lecture, user, requestKey } from '../../services/api'
interface HistoryItem { questionId:string; handoutId:string; lectureId:string; sequence:number; title:string; label:string; text:string; previews:string[]; state:string; latestKind:string; firstWrongAt:string; retests:number; failedRetests:number; events:{id:string;state:string;kind:string;recordedAt:string;displayTime?:string}[] }
const displayTime = (iso:string) => { const d=new Date(Date.parse(iso)+8*60*60*1000); if(!Number.isFinite(d.getTime()))return '时间未知'; const n=(v:number)=>String(v).padStart(2,'0'); return d.getUTCFullYear()+'-'+n(d.getUTCMonth()+1)+'-'+n(d.getUTCDate())+' '+n(d.getUTCHours())+':'+n(d.getUTCMinutes()) }
const labels:Record<string,string>={wrong:'错误',correct:'正确',unfinished:'未完成',unmarked:'未标记'}
Page({
 data:{page:0,total:0,hasMore:false,loading:false,classId:'',courseId:'',students:[] as Named[],studentIndex:0,studentId:'',items:[] as (HistoryItem & {checked:boolean;status:string;date:string})[],selected:[] as string[],lectures:[] as (Named & {id:string})[],lectureIndex:0,from:'',to:'',stateIndex:0,states:['全部历史错题','仍为错误','已标正确','未完成'],busy:false,detail:null as HistoryItem|null,query:''},
 loadSequence:0,
 onLoad(q:Record<string,string>){this.setData({classId:q.classId||'',courseId:q.courseId||'',studentId:q.studentId||''})},
 onShow(){void this.loadClass()},
 loadClass(){return run(async()=>{const r=await api<{students:Named[];lectures:Lecture[]}>('/classes/'+this.data.classId+'?courseId='+this.data.courseId);const index=Math.max(0,r.students.findIndex(s=>s.id===this.data.studentId));this.setData({students:r.students,studentIndex:index,studentId:r.students[index]?.id||'',lectures:[{id:'',name:'全部讲次'},...r.lectures.map(l=>({id:l.id,name:'第'+l.sequence+'讲 · '+l.title}))]});await this.load()})},
 async load(append=false){
   if(append && (this.data.loading || !this.data.hasMore))return;
   const sequence=++this.loadSequence;const studentId=this.data.studentId;
   if(!studentId){this.setData({items:[],selected:[],page:0,total:0,hasMore:false});return}
   const page=append?this.data.page+1:1;this.setData({loading:true,...(append?{}:{selected:[],items:[],page:0,total:0,hasMore:false})});
   try{
     const query=['courseId='+this.data.courseId,'page='+page,'pageSize=50'];
     if(this.data.from)query.push('from='+encodeURIComponent(this.data.from+'T00:00:00+08:00'));
     if(this.data.to)query.push('to='+encodeURIComponent(this.data.to+'T23:59:59.9999999+08:00'));
     if(this.data.lectureIndex)query.push('lectureId='+this.data.lectures[this.data.lectureIndex].id);
     if(this.data.stateIndex)query.push('state='+['all','wrong','correct','unfinished'][this.data.stateIndex]);
     if(this.data.query.trim())query.push('query='+encodeURIComponent(this.data.query.trim()));
     const r=await api<{items:HistoryItem[];total:number;hasMore:boolean}>('/students/'+studentId+'/history?'+query.join('&'));
     if(sequence!==this.loadSequence || studentId!==this.data.studentId)return;
     const next=r.items.map(x=>({...x,checked:this.data.selected.includes(x.questionId),status:(x.latestKind==='retest'?'复测':'批改')+' · '+(labels[x.state]||x.state),date:displayTime(x.firstWrongAt).slice(0,10)}));
     this.setData({items:append?[...this.data.items,...next.filter(x=>!this.data.items.some(old=>old.questionId===x.questionId))]:next,page,total:r.total,hasMore:r.hasMore});
   }finally{if(sequence===this.loadSequence)this.setData({loading:false})}
 },
 onReachBottom(){void run(()=>this.load(true))},
 more(){void run(()=>this.load(true))},
 student(e:WechatMiniprogram.PickerChange){const i=Number(e.detail.value);this.setData({studentIndex:i,studentId:this.data.students[i].id,detail:null,selected:[],items:[]});void run(()=>this.load())},
 filter(e:WechatMiniprogram.PickerChange){this.setData({[e.currentTarget.dataset.field]:e.currentTarget.dataset.field.endsWith('Index')?Number(e.detail.value):e.detail.value});void run(()=>this.load())},
 input(e:WechatMiniprogram.Input){this.setData({query:e.detail.value})}, search(){void run(()=>this.load())},
 clear(){this.setData({from:'',to:'',lectureIndex:0,stateIndex:0,query:''});void run(()=>this.load())},
 select(e:WechatMiniprogram.CustomEvent<{value:string[]}>){this.setData({selected:e.detail.value,items:this.data.items.map(x=>({...x,checked:e.detail.value.includes(x.questionId)}))})},
 all(){const selected=this.data.selected.length===this.data.items.length?[]:this.data.items.map(x=>x.questionId);this.setData({selected,items:this.data.items.map(x=>({...x,checked:selected.includes(x.questionId)}))})},
 detail(e:WechatMiniprogram.TouchEvent){return run(async()=>{const student=this.data.studentId;const detail=await api<HistoryItem>('/students/'+student+'/history/'+e.currentTarget.dataset.id+'?courseId='+this.data.courseId);if(student===this.data.studentId)this.setData({detail:{...detail,events:detail.events.map(event=>({...event,displayTime:displayTime(event.recordedAt)}))}})})}, close(){this.setData({detail:null})},keep(){},
 image(e:WechatMiniprogram.TouchEvent){wx.previewImage({urls:this.data.detail!.previews,current:e.currentTarget.dataset.url})},
 retest(e:WechatMiniprogram.TouchEvent){if(this.data.busy||!this.data.detail)return;return run(async()=>{this.setData({busy:true});try{const storageKey='siyue-retest:'+user()?.id+':'+this.data.studentId+':'+this.data.detail!.questionId;const pending=wx.getStorageSync(storageKey);if(pending && pending.state!==e.currentTarget.dataset.state)throw new Error('上次复测提交尚未确认，请先再次点击上次的结果');const attempt=pending||{state:e.currentTarget.dataset.state,requestKey:requestKey()};wx.setStorageSync(storageKey,attempt);await api('/students/'+this.data.studentId+'/retests','POST',{courseId:this.data.courseId,questionId:this.data.detail!.questionId,...attempt});wx.removeStorageSync(storageKey);this.setData({detail:null});await this.load()}finally{this.setData({busy:false})}})},
 export(){if(this.data.busy)return;return run(async()=>{this.setData({busy:true});try{const r=await api<{batchId:string}>('/students/'+this.data.studentId+'/history/export','POST',{courseId:this.data.courseId,questionIds:this.data.selected});wx.navigateTo({url:'/pages/export/export?batchId='+r.batchId})}finally{this.setData({busy:false})}})},
 report(){wx.navigateTo({url:'/pages/learning-support/learning-support?mode=report&studentId='+this.data.studentId+'&courseId='+this.data.courseId+'&from='+this.data.from+'&to='+this.data.to})},
 variants(){if(!this.data.selected.length)return;wx.navigateTo({url:'/pages/learning-support/learning-support?mode=variants&studentId='+this.data.studentId+'&courseId='+this.data.courseId+'&questionIds='+this.data.selected.join(',')})}
})
