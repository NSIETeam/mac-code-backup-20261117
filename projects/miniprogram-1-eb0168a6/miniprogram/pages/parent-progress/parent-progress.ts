import {parentApi,parentLogin} from '../../services/parent-api'
interface Child {id:string;name:string;state:string;active:boolean;checkCode:string;className:string}
interface PracticeQuestion {id:string;stem:string;material:string;answer:string;analysis:string;source:string;license:string}
interface Stage {practice?:PracticeQuestion[];practiceBatches?:PracticeQuestion[][];id:string;title:string;from:string;to:string;problem:string;evidence:string;adjustment:string;result:string;actionState:string;nextReviewOn:string}
interface Detail {name:string;stages:Stage[];homework:{id:string;title:string;dueOn:string;state:string;submitted:boolean;canSubmit:boolean}[]}
Page({
 data:{busy:false,error:'',available:true,loaded:false,children:[] as Child[],childId:'',detail:null as Detail|null,tab:'homework',revealed:{} as Record<string,boolean>,practiceBatch:{} as Record<string,number>,binding:false,name:'',relationship:'',hasTicket:false},ticket:'',epoch:0,
 onLoad(q:Record<string,string>){this.ticket=q.t||'';this.setData({hasTicket:!!this.ticket})},
 onShow(){return this.load()},onHide(){this.epoch++},
 async load(){const epoch=++this.epoch;this.setData({busy:true,error:'',children:[],detail:null,loaded:false});try{const status=await parentApi<{wechatAvailable:boolean}>('/status');if(epoch!==this.epoch)return;this.setData({available:status.wechatAvailable});if(!status.wechatAvailable)return;
 await parentLogin();const children=await parentApi<Child[]>('/children');if(epoch!==this.epoch)return;this.setData({children,loaded:true});const child=children.find(c=>c.id===this.data.childId&&c.active)||children.find(c=>c.active);if(child)await this.openChild(child.id,epoch);
 }catch(e){if(epoch===this.epoch)this.fail(e)}finally{if(epoch===this.epoch)this.setData({busy:false})}},
 fail(e:unknown){this.setData({error:e instanceof Error?e.message:'操作失败，请重试'})},
 async openChild(id:string,epoch?:number){epoch=epoch??this.epoch;this.setData({detail:null,childId:id,error:'',revealed:{},practiceBatch:{}});const detail=await parentApi<Detail>('/children/'+id);if(epoch===this.epoch&&id===this.data.childId){
  for(const stage of detail.stages){
   const questions=stage.practice||[]
   stage.practiceBatches=[]
   for(let start=0;start<questions.length;start+=5)stage.practiceBatches.push(questions.slice(start,start+5))
   delete stage.practice
  }
  this.setData({detail})
 }},
 async child(e:WechatMiniprogram.TouchEvent){if(this.data.busy)return;const c=this.data.children[Number(e.currentTarget.dataset.index)];if(!c)return;this.setData({childId:c.id,detail:null,error:'',binding:false});if(!c.active)return;this.setData({busy:true});try{await this.openChild(c.id)}catch(e){this.fail(e)}finally{this.setData({busy:false})}},
 changePracticeBatch(e:WechatMiniprogram.TouchEvent){
  const id=String(e.currentTarget.dataset.id)
  const count=this.data.detail?.stages.find(stage=>stage.id===id)?.practiceBatches?.length||0
  if(count<2)return
  const next=((this.data.practiceBatch[id]||0)+1)%count
  this.setData({practiceBatch:{...this.data.practiceBatch,[id]:next}})
 },
 revealPractice(e:WechatMiniprogram.TouchEvent){
  const id=String(e.currentTarget.dataset.key)
  this.setData({revealed:{...this.data.revealed,[id]:!this.data.revealed[id]}})
 },
 tab(e:WechatMiniprogram.TouchEvent){this.setData({tab:e.currentTarget.dataset.tab})},add(){this.setData({binding:true,name:'',relationship:'',error:''})},cancel(){this.setData({binding:false})},name(e:WechatMiniprogram.Input){this.setData({name:e.detail.value})},relationship(e:WechatMiniprogram.Input){this.setData({relationship:e.detail.value})},
 async requestBinding(){if(this.data.busy)return;this.setData({busy:true,error:''});try{await parentApi('/bindings','POST',{ticket:this.ticket,childName:this.data.name.trim(),relationship:this.data.relationship.trim()});this.setData({binding:false});await this.load()}catch(e){this.fail(e)}finally{this.setData({busy:false})}},
 async submit(e:WechatMiniprogram.TouchEvent){if(this.data.busy)return;this.setData({busy:true,error:''});try{const id=e.currentTarget.dataset.id;await parentApi('/tasks/'+id+'/open','POST',{bindingId:this.data.childId});wx.navigateTo({url:'/pages/homework-submit/homework-submit?taskId='+encodeURIComponent(id)+'&bindingId='+encodeURIComponent(this.data.childId)})}catch(e){this.fail(e)}finally{this.setData({busy:false})}},
 onShareAppMessage(){return {title:'思跃 · 学情分析',path:'/pages/parent-progress/parent-progress'}}
})
