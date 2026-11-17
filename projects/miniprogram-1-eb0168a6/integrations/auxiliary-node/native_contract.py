"""Wire v2: fixed PDF/OCR operations; paths and executable names never come from requests."""
import hashlib,json,re
VERSION='native-v2'
SHA=re.compile(r'^[0-9a-f]{64}$')

def options(tool,args):
    if not isinstance(args,list) or len(args)>40 or any(not isinstance(x,str) or len(x)>100 for x in args): raise ValueError('Invalid options')
    out=[];seen=set(); i=0
    flags={'pdftoppm':{'-singlefile','-png'},'pdftotext':{'-bbox-layout','-layout','-raw'},'tesseract':{'tsv'},'rapid':set()}
    limits={'-f':(1,500),'-l':(1,500),'-r':(36,360),'-x':(0,20000),'-y':(0,20000),'-W':(1,20000),'-H':(1,20000),'-scale-to':(32,3000),'--psm':(3,13)}
    allowed={'pdftoppm':{'-f','-l','-r','-x','-y','-W','-H','-scale-to'},'pdftotext':{'-f','-l','-enc'},'tesseract':{'-l','--psm','-c'},'rapid':set()}
    if tool not in flags:raise ValueError('Unsupported tool')
    while i<len(args):
        name=args[i];i+=1
        if name in seen:raise ValueError('Duplicate option')
        seen.add(name);out.append(name)
        if name in flags[tool]:continue
        if name not in allowed[tool] or i>=len(args):raise ValueError('Unsupported option')
        value=args[i];i+=1;out.append(value)
        if name=='-l' and tool=='tesseract':
            if value not in ('chi_sim+eng','eng','chi_sim'):raise ValueError('Unsupported language')
        elif name=='-enc':
            if value!='UTF-8':raise ValueError('Invalid encoding')
        elif name=='-c':
            if value!='tessedit_char_whitelist=0123456789':raise ValueError('Unsupported setting')
        else:
            if not re.fullmatch(r'[0-9]+',value):raise ValueError('Invalid number')
            low,high=limits[name]
            if not low<=int(value)<=high:raise ValueError('Number outside contract')
    if tool in ('pdftoppm','pdftotext'):
        if '-f' not in out or '-l' not in out:raise ValueError('A single page is required')
        if out[out.index('-f')+1]!=out[out.index('-l')+1]:raise ValueError('Single page only')
    if tool=='pdftoppm' and not {'-singlefile','-png'}.issubset(seen):raise ValueError('PNG single page required')
    return out

def validate(payload):
    if payload.get('mode')!='native' or payload.get('nativeVersion')!=VERSION or not SHA.fullmatch(payload.get('inputSha','')):raise ValueError('Invalid contract')
    if 'inputSize' in payload and (type(payload['inputSize']) is not int or not 0<payload['inputSize']<=128*1024**2):raise ValueError('Invalid input size')
    return options(payload.get('tool'),payload.get('options'))

def cache_key(payload,engine):
    return hashlib.sha256(json.dumps([VERSION,engine,payload['tool'],payload['inputSha'],payload['options']],sort_keys=True,separators=(',',':')).encode()).hexdigest()
