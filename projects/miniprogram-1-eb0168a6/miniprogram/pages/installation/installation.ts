import { api, run, user } from '../../services/api'
interface Installer { version:string;fileName:string;size:number;sha256:string;url:string }
Page({
  data: { installer: null as Installer|null, sizeLabel:'', busy:false, progress:0, savedPath:'', error:'' },
  onShow() { if (user()?.role !== 'admin') { wx.reLaunch({url:'/pages/home/home'}); return } return this.retry() },
  async refresh(): Promise<Installer> {
    try {
      const installer = await api<Installer>('/admin/installer-download','POST');
      const changed = this.data.installer?.sha256 !== installer.sha256;
      this.setData({installer,sizeLabel:(installer.size/1024/1024).toFixed(1)+' MB',error:'', ...(changed ? {savedPath:''} : {})});
      return installer;
    } catch (e) {
      this.setData({installer:null,savedPath:'',error:e instanceof Error ? e.message : '安装包信息加载失败，请重试'});
      throw e;
    }
  },
  retry() { if(this.data.busy)return; return run(async()=>{this.setData({busy:true});try {await this.refresh()}finally{this.setData({busy:false})}}) },
  copyLink() { if(this.data.busy)return; return run(async()=>{this.setData({busy:true});try {const installer=await this.refresh();wx.setClipboardData({data:installer.url})}finally{this.setData({busy:false})}}) },
  download() { if(this.data.busy)return;return run(async()=>{
    this.setData({busy:true,progress:0,error:''});
    try {
      const installer=await this.refresh();
      const path=await new Promise<string>((resolve,reject)=>{const task=wx.downloadFile({url:installer.url,timeout:300000,success:r=>r.statusCode===200?resolve(r.tempFilePath):reject(new Error('下载失败，请重新获取安装包')),fail:()=>reject(new Error('下载中断，请重试或复制链接到电脑下载'))});task.onProgressUpdate(r=>this.setData({progress:r.progress}))});
      const savedPath=wx.env.USER_DATA_PATH+'/'+installer.fileName;
      // Copy permits replacing a previously downloaded package with the same filename.
      await new Promise<void>((resolve,reject)=>wx.getFileSystemManager().copyFile({srcPath:path,destPath:savedPath,success:()=>resolve(),fail:()=>reject(new Error('保存失败，请复制链接到 Windows 电脑下载'))}));
      this.setData({savedPath,progress:100});wx.showToast({title:'安装包已下载',icon:'success'});
    }catch(e){this.setData({error:e instanceof Error?e.message:'下载失败，请重试'});throw e}
    finally{this.setData({busy:false})}
  }) },
  share() { if(this.data.savedPath&&this.data.installer)wx.shareFileMessage({filePath:this.data.savedPath,fileName:this.data.installer.fileName,fail:()=>wx.showModal({title:'转发未完成',content:'可以复制电脑下载链接，在 Windows 电脑上下载。',showCancel:false})}) }
})
