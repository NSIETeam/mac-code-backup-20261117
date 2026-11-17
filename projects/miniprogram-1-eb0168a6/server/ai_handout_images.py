"""Source-preserving AI layout checks and previews; no OCR/model or content rewriting."""
import json, sys, math
from PIL import Image, ImageChops, ImageStat
Image.MAX_IMAGE_PIXELS = 45_000_000

def layout(path, plan):
    im = Image.open(path).convert('L'); w,h = im.size
    ink = im.point(lambda p: 255 if p < 210 else 0, '1').convert('L')
    regions = plan['regions']
    # Only snap an existing edge within a tiny neighbourhood. Never fill gaps or
    # expand to page edges: that would hide a missed question as another one's crop.
    for r in regions:
        l,t,rr,b=[round(v*s) for v,s in zip(r['bounds'],[w,h,w,h])]
        def snap(value,axis):
            dimension=h if axis=='y' else w
            radius=min(18,max(2,round(dimension*.006)))
            candidates=range(max(1,value-radius),min(dimension-1,value+radius)+1)
            def score(v):
                box=(l,v-1,rr,v+1) if axis=='y' else (v-1,t,v+1,b)
                return (ImageStat.Stat(ink.crop(box)).sum[0],abs(v-value))
            return min(candidates,key=score) if value not in (0,dimension) else value
        nt,nb=snap(t,'y'),snap(b,'y');nl,nr=snap(l,'x'),snap(rr,'x')
        r['bounds']=[nl/w,nt/h,nr/w,nb/h]
    # Models sometimes overlap two neighbouring whitespace margins by a few pixels.
    # Resolve only genuinely blank intersections; never remove overlapping ink.
    for i,a in enumerate(regions):
        for b in regions[i+1:]:
            x,y=a['bounds'],b['bounds'];l,t,rr,bb=max(x[0],y[0]),max(x[1],y[1]),min(x[2],y[2]),min(x[3],y[3])
            if l>=rr or t>=bb:continue
            if ImageStat.Stat(ink.crop((round(l*w),round(t*h),round(rr*w),round(bb*h)))).sum[0]>0:continue
            if bb-t<=.012 and x[1]<y[1]:x[3]=y[1]=(t+bb)/2
            elif rr-l<=.012 and x[0]<y[0]:x[2]=y[0]=(l+rr)/2
    coverage=Image.new('L',(w,h),0); cut_ink=0
    for r in regions:
        l,t,rr,b=[round(v*s) for v,s in zip(r['bounds'],[w,h,w,h])]
        coverage.paste(255,(l,t,rr,b))
        # Check all interior edges, including vertical boundaries on multi-column pages.
        for box in [(max(0,l-1),t,min(w,l+1),b),(max(0,rr-1),t,min(w,rr+1),b),(l,max(0,t-1),rr,min(h,t+1)),(l,max(0,b-1),rr,min(h,b+1))]:
            if box[2]-box[0]<=1 or box[3]-box[1]<=1: continue
            cut_ink=max(cut_ink,round(ImageStat.Stat(ink.crop(box)).sum[0]/255))
    missing=ImageChops.subtract(ink,coverage)
    return {'regions':regions,'missingPixels':round(ImageStat.Stat(missing).sum[0]/255),'cutInk':cut_ink,'missingBounds':missing.getbbox()}

def preview(plan):
    chunks=[]; physical=0
    for item in plan['items']:
        with Image.open(item['path']) as im:
            w,h=im.size; b=item['bounds']; crop=im.convert('RGB').crop((math.floor(b[0]*w),math.floor(b[1]*h),math.ceil(b[2]*w),math.ceil(b[3]*h)))
            width=min(920,crop.width); scale=width/crop.width
            if scale!=1: crop=crop.resize((width,max(1,round(crop.height*scale))),Image.Resampling.LANCZOS)
            physical+=(b[3]-b[1])*h*72/180
            chunks.append(crop)
    width=max(x.width for x in chunks); paths=[]; canvas=None; y=0
    for crop in chunks:
        for start in range(0,crop.height,1600):
            strip=crop.crop((0,start,crop.width,min(crop.height,start+1600)))
            if canvas is None or y+strip.height>1800:
                if canvas is not None:
                    p=plan['prefix']+f'-{len(paths)+1}.png';canvas.crop((0,0,width,y)).save(p);paths.append(p)
                canvas=Image.new('RGB',(width,1800),'white');y=0
            canvas.paste(strip,(0,y));y+=strip.height+12
    if canvas is not None:
        p=plan['prefix']+f'-{len(paths)+1}.png';canvas.crop((0,0,width,min(y,1800))).save(p);paths.append(p)
    return {'paths':paths,'half':physical<175 and len(paths)==1}

if __name__=='__main__':
    mode,source,plan_path,out=sys.argv[1:]
    plan=json.load(open(plan_path))
    if mode=='crop':
        with Image.open(source) as im:
            im.crop((0,round(plan['top']*im.height),im.width,round(plan['bottom']*im.height))).save(out)
    elif mode=='resize':
        with Image.open(source) as im:
            im.thumbnail((1600,2000)); im.convert('RGB').save(out)
    else:
        result=layout(source,plan) if mode=='layout' else preview(plan)
        with open(out,'w') as f:json.dump(result,f,ensure_ascii=False)
