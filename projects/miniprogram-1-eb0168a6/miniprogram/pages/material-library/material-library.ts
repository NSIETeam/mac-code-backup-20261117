import {api, run, openFile} from '../../services/api'
interface Material {id:string;courseId:string;courseName:string;role:string;audience:string;title:string;fileName:string;format:string;materialVersion:number;originalRelativePath:string;context:string;downloadLabel:string}
interface Course {courseId:string;courseName:string;materials:Material[]}
const roles:Record<string,string>={handout:'讲义',practice:'练习',solution:'解析',notes:'笔记',source:'源格式',reference:'参考'}
const audiences:Record<string,string>={student:'学生用',teacher:'教师用',both:'师生共用'}
Page({
 data:{path:'',query:'',folders:[] as {name:string;path:string;count:number}[],files:[] as Material[],loading:true,failed:false,busy:false},
 items:[] as Material[],
 questionBank(){wx.navigateTo({url:'/pages/question-bank/question-bank'})},
 upload(){wx.navigateTo({url:'/pages/quick-add/quick-add?kind=lecture'})},
 onLoad(){return this.load()},
 load(){return run(async()=>{this.setData({loading:true,failed:false});try{
  const data=await api<{revision:number;courses:Course[]}>('/course-material-library');
  this.items=data.courses.flatMap(c=>c.materials.map(m=>({...m,courseId:c.courseId,courseName:c.courseName,context:[roles[m.role],audiences[m.audience],c.courseName,'v'+m.materialVersion,m.format.toUpperCase()].filter(Boolean).join(' · '),downloadLabel:['pdf','doc','docx','png','jpg','jpeg'].includes(m.format)?'原稿下载':'复制原稿链接'})));this.filter();
 }catch(error){this.setData({failed:true});throw error}finally{this.setData({loading:false})}})},
 filter(){const prefix=this.data.path?this.data.path+'/':'';const query=this.data.query.trim().toLocaleLowerCase();const folders=new Map<string,number>(),files:Material[]=[];
  for(const m of this.items){if(!m.originalRelativePath.startsWith(prefix))continue;if(query){if((m.originalRelativePath+' '+m.title+' '+m.courseName).toLocaleLowerCase().includes(query))files.push(m);continue}const tail=m.originalRelativePath.slice(prefix.length),slash=tail.indexOf('/');if(slash<0)files.push(m);else{const name=tail.slice(0,slash);folders.set(name,(folders.get(name)||0)+1)}}
  this.setData({folders:[...folders].sort(([a],[b])=>a.localeCompare(b)).map(([name,count])=>({name,count,path:prefix+name})),files:files.sort((a,b)=>a.originalRelativePath.localeCompare(b.originalRelativePath)||a.materialVersion-b.materialVersion)})
 },
 search(e:WechatMiniprogram.Input){this.setData({query:e.detail.value});this.filter()},
 folder(e:WechatMiniprogram.TouchEvent){this.setData({path:e.currentTarget.dataset.path,query:''});this.filter()},
 up(){this.setData({path:this.data.path.split('/').slice(0,-1).join('/'),query:''});this.filter()},
 root(){this.setData({path:'',query:''});this.filter()},
 view(e:WechatMiniprogram.TouchEvent){const {id,course,name}=e.currentTarget.dataset;wx.navigateTo({url:'/pages/material-edition/material-edition?id='+encodeURIComponent(id)+'&courseId='+encodeURIComponent(course)+'&fileName='+encodeURIComponent(name)})},
 download(e:WechatMiniprogram.TouchEvent){if(this.data.busy)return;const {id,course}=e.currentTarget.dataset;return run(async()=>{this.setData({busy:true});try{
  const f=await api<{url:string;fileName:string;format:string}>('/course-material-library/'+course+'/'+id+'/file');
  if(f.format==='pdf'||f.format==='doc'||f.format==='docx')await openFile(f.url,f.format,f.fileName);
  else if(['png','jpg','jpeg'].includes(f.format))await new Promise<void>((resolve,reject)=>wx.previewImage({urls:[f.url],success:()=>resolve(),fail:()=>reject(new Error('图片暂时无法打开，请重试'))}));
  else await new Promise<void>((resolve,reject)=>wx.setClipboardData({data:f.url,success:()=>{wx.showToast({title:'链接10分钟内有效',icon:'none'});resolve()},fail:()=>reject(new Error('复制下载链接失败，请重试'))}));
 }finally{this.setData({busy:false})}})}
})
