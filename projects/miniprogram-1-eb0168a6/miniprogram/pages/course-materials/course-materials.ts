import {api, run, Named, openFile} from '../../services/api'
interface Material { id:string; courseId:string; lectureIds:string[]; role:string; audience:string; title:string; fileName:string; format:string; materialVersion:number; bytes:number; previousMaterialId?:string|null; companionOfMaterialId?:string|null }
interface MaterialLecture {id:string;sequence:number;title:string}
interface MaterialResponse {courseId:string;courseName:string;lectures:MaterialLecture[];materials:Material[]}
interface Card extends Material {context:string;downloadLabel:string}
const roles=[{id:'',name:'全部'},{id:'handout',name:'讲义'},{id:'practice',name:'练习'},{id:'solution',name:'解析'},{id:'notes',name:'笔记'},{id:'source',name:'源格式'},{id:'reference',name:'参考'}]
const audiences:Record<string,string>={student:'学生用',teacher:'教师用',both:'师生共用'}
Page({
 data:{classId:'',courseId:'',courses:[] as Named[],courseIndex:0,roles,role:'',lectures:[{id:'',name:'全部讲次与课程通用资料'}],lectureIndex:0,lectureId:'',query:'',cards:[] as Card[],loading:true,failed:false,busy:false},
 response:null as MaterialResponse|null,
 requestSerial:0,
 onLoad(query:Record<string,string>){this.setData({classId:query.classId||'',courseId:query.courseId||'',lectureId:query.lectureId||''});return this.start()},
 onUnload(){this.requestSerial++},
 start(){return run(async()=>{
  try{
   if(this.data.classId){const detail=await api<{courses:Named[]}>('/classes/'+this.data.classId);const courses=detail.courses||[];const selected=courses.findIndex(c=>c.id===this.data.courseId);this.setData({courses,courseIndex:Math.max(0,selected),courseId:courses[Math.max(0,selected)]?.id||''})}
   if(!this.data.courseId){this.setData({loading:false,cards:[]});return}
   await this.fetchMaterials()
  }catch(error){this.setData({loading:false,failed:true});throw error}
 })},
 async fetchMaterials(){
  const serial=++this.requestSerial;this.setData({loading:true,failed:false,cards:[]});
  try{
   const response=await api<MaterialResponse>('/courses/'+this.data.courseId+'/materials');if(serial!==this.requestSerial)return;this.response=response;
   const lectures=[{id:'',name:'全部讲次与课程通用资料'},...response.lectures.map(l=>({id:l.id,name:'第'+l.sequence+'讲 · '+l.title}))];const lectureIndex=Math.max(0,lectures.findIndex(l=>l.id===this.data.lectureId));
   this.setData({lectures,lectureIndex,lectureId:lectures[lectureIndex].id,...(!this.data.classId?{courses:[{id:response.courseId,name:response.courseName}]}:{})});this.filter();
  }catch(error){if(serial===this.requestSerial)this.setData({failed:true});throw error}
  finally{if(serial===this.requestSerial)this.setData({loading:false})}
 },
 filter(){const response:MaterialResponse|null=this.response;if(!response)return;this.setData({cards:response.materials.filter(m=>(!this.data.role||m.role===this.data.role)&&(!this.data.lectureId||m.lectureIds.includes(this.data.lectureId))&&(!this.data.query.trim()||(m.title+' '+m.fileName).toLocaleLowerCase().includes(this.data.query.trim().toLocaleLowerCase()))).map(m=>{
  const lessons=m.lectureIds.map(id=>response.lectures.find(l=>l.id===id)).filter((x):x is MaterialLecture=>!!x).map(l=>'第'+l.sequence+'讲').join('、')||'课程通用';const primary=response.materials.find(x=>x.id===m.companionOfMaterialId);const previous=response.materials.find(x=>x.id===m.previousMaterialId);
  return {...m,context:[roles.find(r=>r.id===m.role)?.name,audiences[m.audience],lessons,'资料版本 '+m.materialVersion,m.format.toUpperCase(),primary?'配套：'+primary.title:'',previous?'前版：v'+previous.materialVersion:''].filter(Boolean).join(' · '),downloadLabel:['pdf','doc','docx','png','jpg','jpeg'].includes(m.format)?'原稿下载':'复制原稿链接'}
 })})},
 search(e:WechatMiniprogram.Input){this.setData({query:e.detail.value});this.filter()},
 view(e:WechatMiniprogram.TouchEvent){const m=this.response?.materials.find((x:Material)=>x.id===e.currentTarget.dataset.id);if(!m)return;wx.navigateTo({url:'/pages/material-edition/material-edition?id='+encodeURIComponent(m.id)+'&courseId='+encodeURIComponent(m.courseId)+'&fileName='+encodeURIComponent(m.fileName)+'&sourceScope=course'})},
 chooseRole(e:WechatMiniprogram.TouchEvent){this.setData({role:e.currentTarget.dataset.id});this.filter()},
 chooseLecture(e:WechatMiniprogram.PickerChange){const i=Number(e.detail.value);this.setData({lectureIndex:i,lectureId:this.data.lectures[i].id});this.filter()},
 chooseCourse(e:WechatMiniprogram.PickerChange){const i=Number(e.detail.value);this.setData({courseIndex:i,courseId:this.data.courses[i].id,lectureId:'',lectureIndex:0});return run(()=>this.fetchMaterials())},
 retry(){return this.start()},
 download(e:WechatMiniprogram.TouchEvent){if(this.data.busy)return;const id=e.currentTarget.dataset.id;return run(async()=>{this.setData({busy:true});try{
  const file=await api<{url:string;fileName:string;format:string}>('/courses/'+this.data.courseId+'/materials/'+id+'/file');
  if(file.format==='pdf'||file.format==='doc'||file.format==='docx')await openFile(file.url,file.format,file.fileName);
  else if(['png','jpg','jpeg'].includes(file.format))await new Promise<void>((resolve,reject)=>wx.previewImage({urls:[file.url],success:()=>resolve(),fail:()=>reject(new Error('图片暂时无法打开，请重试'))}));
  else await new Promise<void>((resolve,reject)=>wx.setClipboardData({data:file.url,success:()=>{wx.showToast({title:'链接10分钟内有效',icon:'none'});resolve()},fail:()=>reject(new Error('复制下载链接失败，请重试'))}));
 }finally{this.setData({busy:false})}})}
})
