import {api, run, openFile} from '../../services/api'
interface Edition {id:string;number:number;createdAt:string;actor:string;docxReady:boolean;pdfReady:boolean;date?:string}
interface EditionSummary {materialId:string;currentEditionId:string|null;revision:number;history:Edition[];canEdit:boolean}
Page({
 data:{materialId:'',courseId:'',fileName:'',sourceScope:'library',current:null as Edition|null,history:[] as Edition[],loading:true,failed:false,busy:false},
 onLoad(q:Record<string,string>){this.setData({materialId:q.id||'',courseId:q.courseId||'',fileName:q.fileName||'',sourceScope:q.sourceScope==='course'?'course':'library'});return this.load()},
 load(){return run(async()=>{this.setData({loading:true,failed:false});try{
  if(!this.data.materialId)throw new Error('缺少教材信息，请返回重新打开');
  const summary=await api<EditionSummary>('/material-versions/by-material/'+this.data.materialId);
  const editions=summary.history.map(e=>({...e,date:e.createdAt.slice(0,10)}));const current=editions.find(e=>e.id===summary.currentEditionId)||null;
  if(summary.currentEditionId&&!current)throw new Error('教材版本信息不完整，请刷新后重试');
  this.setData({current,history:editions.filter(e=>e.id!==summary.currentEditionId).sort((a,b)=>b.number-a.number)});
 }catch(error){this.setData({failed:true});throw error}finally{this.setData({loading:false})}})},
 editionFile(e:WechatMiniprogram.TouchEvent){if(this.data.busy)return;const id=String(e.currentTarget.dataset.id),format=String(e.currentTarget.dataset.format);if(format!=='pdf'&&format!=='docx')return;
  return run(async()=>{this.setData({busy:true});try{const file=await api<{url:string;fileName:string;expiresAt:string}>('/material-versions/'+id+'/file?format='+format);await openFile(file.url,format,file.fileName)}finally{this.setData({busy:false})}})
 },
 original(){if(this.data.busy)return;return run(async()=>{this.setData({busy:true});try{
  const f=await api<{url:string;fileName:string;format:string}>((this.data.sourceScope==='course'?'/courses/'+this.data.courseId+'/materials/':'/course-material-library/'+this.data.courseId+'/')+this.data.materialId+'/file');
  if(f.format==='pdf'||f.format==='doc'||f.format==='docx')await openFile(f.url,f.format,f.fileName);
  else if(['png','jpg','jpeg'].includes(f.format))await new Promise<void>((resolve,reject)=>wx.previewImage({urls:[f.url],success:()=>resolve(),fail:()=>reject(new Error('图片暂时无法打开，请重试'))}));
  else await new Promise<void>((resolve,reject)=>wx.setClipboardData({data:f.url,success:()=>{wx.showToast({title:'原稿链接10分钟内有效',icon:'none'});resolve()},fail:()=>reject(new Error('复制原稿链接失败，请重试'))}));
 }finally{this.setData({busy:false})}})}
})
