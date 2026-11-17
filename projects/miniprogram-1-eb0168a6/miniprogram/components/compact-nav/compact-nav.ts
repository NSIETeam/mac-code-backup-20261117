Component({
 properties:{title:{type:String,value:''}},
 data:{top:24,right:104,height:32},
 lifetimes:{attached(){
  try{const rect=wx.getMenuButtonBoundingClientRect(),info=wx.getSystemInfoSync();
   const valid=rect.height>0&&rect.left>0;
   this.setData({top:valid?rect.top:(info.statusBarHeight||20)+4,right:valid?info.windowWidth-rect.left+8:104,height:valid?rect.height:32});
  }catch{/* Retain compact defaults when metrics are unavailable. */}
 }},
 methods:{back(){if(getCurrentPages().length>1)wx.navigateBack({delta:1});else if(getCurrentPages()[0]?.route==='pages/home/home')(wx as unknown as {exitMiniProgram:(options:Record<string,unknown>)=>void}).exitMiniProgram({});else wx.reLaunch({url:'/pages/home/home'})}}
})
