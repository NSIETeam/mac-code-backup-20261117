import {BankDiagram} from '../../services/question-bank'
Component({
 properties:{diagram:{type:Object,value:{}}},
 data:{canvasReady:false,drawError:false},
 lifetimes:{ready(){this.data.canvasReady=true;this.draw()},detached(){this.data.canvasReady=false}},
 observers:{diagram(){if(this.data.canvasReady)this.draw()}},
 methods:{draw(){
  const diagram=this.properties.diagram as BankDiagram|null
  if(!diagram||diagram.width!==360||diagram.height!==270||!Array.isArray(diagram.shapes))return
  const info=wx.getSystemInfoSync(),dpr=Math.max(1,Math.min(4,info.pixelRatio||1))
   this.createSelectorQuery().select('#diagram').fields({node:true,size:true},(result:{node?:WechatMiniprogram.Canvas;width?:number;height?:number})=>{
    if(!this.data.canvasReady||diagram!==this.properties.diagram)return
    try{
     const canvas=result?.node
     if(!canvas||!result.width||!result.height)throw Error('Canvas unavailable')
     const width=result.width,scale=width/360
     if(this.data.drawError)this.setData({drawError:false})
     canvas.width=Math.round(width*dpr);canvas.height=Math.round(270*scale*dpr)
     const c=canvas.getContext('2d');c.scale(dpr*scale,dpr*scale);c.fillStyle='#fff';c.fillRect(0,0,360,270)
     const palette:Record<string,string>={'#315e52':'#1d1d1f','#698675':'#77777d'}
     for(const s of diagram.shapes){
      const v=s.values;if(!Array.isArray(v)||v.some(x=>!Number.isFinite(x)))continue
      const color=palette[s.color.toLowerCase()]??s.color;c.fillStyle=color;c.strokeStyle=color;c.lineWidth=s.width;c.setLineDash(s.dash?[4,4]:[])
      if(s.kind==='line'&&v.length===4){c.beginPath();c.moveTo(v[0],v[1]);c.lineTo(v[2],v[3]);c.stroke()}
      else if(s.kind==='path'&&v.length>=4&&v.length%2===0){c.beginPath();c.moveTo(v[0],v[1]);for(let i=2;i<v.length;i+=2)c.lineTo(v[i],v[i+1]);c.stroke()}
      else if(s.kind==='circle'&&v.length===3){c.beginPath();c.arc(v[0],v[1],v[2],0,2*Math.PI);c.fill()}
      else if(s.kind==='text'&&v.length===2){c.font=s.fontSize+'px sans-serif';c.textAlign=s.align==='center'?'center':'left';c.fillText(s.text,v[0],v[1])}
     }
    }catch{this.setData({drawError:true})}
   }).exec()
 }}
})
