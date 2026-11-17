"""Local image checks. No network or model service; original pixels remain authoritative."""
import json, sys, re, math
from pathlib import Path
from PIL import Image, ImageDraw, ImageChops
Image.MAX_IMAGE_PIXELS = 45_000_000

def mask_of(im):
    return im.convert('L').point(lambda x: 255 if x < 210 else 0, '1')

def audit(path, plan):
    with Image.open(path) as im:
        ink = mask_of(im)
        covered = Image.new('1', im.size)
        draw = ImageDraw.Draw(covered)
        sx, sy = im.width / plan['width'], im.height / plan['height']
        for r in plan['regions']:
            draw.rectangle((math.floor(r[0]*sx)-2, math.floor(r[1]*sy)-2,
                            math.ceil(r[2]*sx)+2, math.ceil(r[3]*sy)+2), fill=255)
        missing = ImageChops.logical_and(ink, ImageChops.invert(covered))
        box = missing.getbbox()
        count = missing.histogram()[255]
        cut_ink=0
        for cut in plan.get('cuts',[]):
            left,y,right=math.floor(cut[0]*sx),round(cut[1]*sy),math.ceil(cut[2]*sx)
            if 2<y<im.height-2:
                cut_ink=max(cut_ink,ink.crop((left,y-1,right,y+1)).histogram()[255])
        return {'missingPixels': count, 'missingBounds': box, 'cutInk':cut_ink}

def ocr(path, tsv):
    with Image.open(path) as im:
        ink = mask_of(im)
        # Row projection protects diagrams, fraction bars and characters at every cut.
        _, rows = ink.getprojection()
        groups = {}
        for raw in Path(tsv).read_text(encoding='utf-8').splitlines():
            c = raw.rstrip('\n').split('\t', 11)
            if len(c) != 12 or c[0] != '5' or not c[11].strip(): continue
            x,y,w,h = map(int,c[6:10]); confidence = float(c[10])
            groups.setdefault(tuple(c[1:5]), []).append((x,y,w,h,confidence,c[11].strip()))
        lines=[]
        for words in groups.values():
            words.sort(key=lambda w:w[0])
            text=' '.join(w[5] for w in words)
            text=re.sub(r'(?<=[\u3400-\u9fff])\s+(?=[\u3400-\u9fff0-9])','',text)
            text=re.sub(r'(?<=[0-9])\s+(?=[讲课节】])','',text)
            lines.append({'text':text,'left':min(w[0] for w in words),'right':max(w[0]+w[2] for w in words),
                          'top':min(w[1] for w in words),'bottom':max(w[1]+w[3] for w in words),
                          'confidence':sum(w[4] for w in words)/len(words)})
        lines.sort(key=lambda l:(l['top'],l['left']))
        runs=[]; start=None
        for y,value in enumerate(rows+[1]):
            if not value and start is None: start=y
            if value and start is not None:
                if y-start>=3:runs.append([start,y])
                start=None
        return {'width':im.width,'height':im.height,'lines':lines,'gaps':runs}

def rapid(path, tsv):
    # Bundled PP-OCRv4 models run locally; use fixed single-thread CPU inference.
    from rapidocr import RapidOCR
    engine = RapidOCR(params={'EngineConfig.onnxruntime.intra_op_num_threads':1,
                              'EngineConfig.onnxruntime.inter_op_num_threads':1,
                              'Global.log_level':'error',
                              'Global.font_path':'/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'})
    result = engine(str(path))
    records=[]
    if result.boxes is not None:
        for i,(box,text,score) in enumerate(zip(result.boxes,result.txts,result.scores)):
            left,top=box.min(axis=0);right,bottom=box.max(axis=0)
            records.append(f'5\t1\t1\t1\t{i+1}\t1\t{math.floor(left)}\t{math.floor(top)}\t{math.ceil(right-left)}\t{math.ceil(bottom-top)}\t{float(score)*100}\t{text}')
    Path(tsv).write_text('\n'.join(records),encoding='utf-8')
    return ocr(path,tsv)

def layout(path, plan):
    """Partition source ink, column first. Never cut through a graphic bounding box.

    A missing safe horizontal cut merges only that group for review, not the page.
    Every returned crop is audited against the complete original pixel mask.
    """
    with Image.open(path) as image:
        ink=mask_of(image); width,height=image.size
        anchors=sorted(plan['anchors'],key=lambda a:(a['left'],a['top']))
        graphics=plan.get('graphics',[])
        def failure(message):
            return {'regions':[], 'columns':0, 'missingPixels':0, 'error':message}
        def runs(values):
            result=[];start=None
            for i,value in enumerate(list(values)+[1]):
                if not value and start is None:start=i
                elif value and start is not None:
                    if i-start>=4:result.append((start,i))
                    start=None
            return result
        def horizontal(left,right,low,high,context_cut=False):
            if high-low<4:return None
            lo=max(0,math.ceil(low));hi=min(height,math.floor(high))
            _,rows=ink.crop((left,lo,right,hi)).getprojection()
            for a,b in reversed(runs(rows)):
                y=lo+(a+b)//2
                if context_cut or not any(g[0]<right and g[2]>left and g[1]+2<y<g[3]-2 for g in graphics):return y
            return None
        clusters=[]
        for anchor in anchors:
            if not clusters or anchor['left']-clusters[-1][0]['left']>width*.24:clusters.append([])
            clusters[-1].append(anchor)
        if len(clusters)>3:return failure('栏数或阅读顺序不明确，已保留原页供校对')
        # Indented subparts within a column do not become independent questions.
        for i,c in enumerate(clusters):
            edge=min(a['left'] for a in c)
            clusters[i]=sorted([a for a in c if a['left']<=edge+max(24,width*.018)],key=lambda a:a['top'])
        if not anchors:
            if not plan.get('allowContinuation'):return failure('没有可确认的题号或跨页衔接')
            clusters=[[]]
        body_top=0;cuts=[0];regions=[]
        if len(clusters)>1:
            first_top=min(a['top'] for a in anchors)
            boundary=horizontal(0,width,0,first_top)
            if boundary is None:return failure('跨栏内容与题目相连，无法安全确定阅读顺序')
            body_top=boundary
            for left_group,right_group in zip(clusters,clusters[1:]):
                low=math.ceil(left_group[0]['left']+width*.15)
                high=math.floor(right_group[0]['left']-3)
                if low>=high:return failure('栏间距不足，无法安全拆题')
                columns,_=ink.crop((low,body_top,high,height)).getprojection()
                options=[]
                for a,b in runs(columns):
                    x=low+(a+b)//2
                    if not any(g[1]<height and g[3]>body_top and g[0]+2<x<g[2]-2 for g in graphics):options.append((b-a,x))
                if not options:return failure('图形或文字跨越栏间边界，已保留整页供校对')
                cuts.append(max(options)[1])
        cuts.append(width)
        def add(left,top,right,bottom,group,checks=()):
            top=max(0,int(top));bottom=min(height,int(bottom))
            if bottom<=top:return
            bbox=ink.crop((left,top,right,bottom)).getbbox()
            if not bbox:return
            x0,y0,x1,y1=bbox
            bounds=[max(left,left+x0-3),max(top,top+y0-3),min(right,left+x1+3),min(bottom,top+y1+3)]
            regions.append({'anchors':[a['index'] for a in group], 'bounds':bounds,
                            'checks':list(checks)+(['merged-region'] if len(group)>1 else [])})
        if body_top:add(0,0,width,body_top,[],['page-prefix'])
        for ci,group in enumerate(clusters):
            left,right=cuts[ci:ci+2];top=body_top
            if group:
                prefix=horizontal(left,right,top,group[0]['top'])
                if prefix is not None and ink.crop((left,top,right,prefix)).getbbox():
                    add(left,top,right,prefix,[],['continuation-prefix']);top=prefix
                current=[group[0]]
                for anchor in group[1:]:
                    # Decorative title images can include white padding extending into
                    # the next question. Only at a recognized context-to-question edge,
                    # a completely blank pixel row is stronger evidence than that box.
                    boundary=horizontal(left,right,max(a['bottom'] for a in current),anchor['top'],
                                        all(a.get('context',False) for a in current) and not anchor.get('context',False))
                    if boundary is None:current.append(anchor)
                    else:
                        add(left,top,right,boundary,current);top=boundary;current=[anchor]
                add(left,top,right,height,current)
            else:add(left,top,right,height,[],['continuation-page'])
        covered=Image.new('1',image.size);draw=ImageDraw.Draw(covered)
        for region in regions:
            l,t,r,b=region['bounds'];draw.rectangle((l,t,r-1,b-1),fill=255)
        missing=ImageChops.logical_and(ink,ImageChops.invert(covered)).histogram()[255]
        return {'regions':regions,'columns':len(clusters),'missingPixels':missing,'error':None}

if __name__=='__main__':
    mode, path, data, output = sys.argv[1:]
    result = audit(path,json.loads(Path(data).read_text())) if mode=='audit' else layout(path,json.loads(Path(data).read_text())) if mode=='layout' else rapid(path,data) if mode=='rapid' else ocr(path,data)
    with open(output,'w',encoding='utf-8') as f:json.dump(result,f,ensure_ascii=False)
