"""Bounded memory inventory, idle trimming, and explicit signed screensaver cleanup.

The separate screensaver action only stops the identified 360 screensaver; it
never stops document, browser, chat or printing processes. No command-line capture.
"""
import ctypes,json,os,sys,time,urllib.request,subprocess,re,hashlib
from pathlib import Path,PureWindowsPath

MiB=1024**2
DWORD=ctypes.c_uint32
SIZE=ctypes.c_size_t
HANDLE=ctypes.c_void_p
BOOL=ctypes.c_int
ALLOWED={'chrome.exe','msedge.exe','wechat.exe','weixin.exe','wechatappex.exe','360se.exe','quark.exe'}

def approved_screensaver(path,signature):
    return (PureWindowsPath(path).name.lower()=='360huabao.scr'
            and signature.get('status')=='Valid'
            and bool(re.search(r'\b(?:Qihu|Qihoo)\b|奇虎',signature.get('subject',''),re.I)))

def image_signature(path):
    def digest():
        with Path(path).open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
    before=digest()
    code="$ErrorActionPreference='Stop';$s=Get-AuthenticodeSignature -LiteralPath $env:SIYUE_MEMORY_IMAGE;@{status=[string]$s.Status;subject=[string]$s.SignerCertificate.Subject}|ConvertTo-Json -Compress"
    result=subprocess.run([os.path.join(os.environ['SystemRoot'],'System32','WindowsPowerShell','v1.0','powershell.exe'),'-NoProfile','-NonInteractive','-Command',code],
        env={**os.environ,'SIYUE_MEMORY_IMAGE':path},capture_output=True,text=True,timeout=15,check=True)
    if digest()!=before:raise ValueError('Screensaver executable changed')
    return json.loads(result.stdout)

class FileTime(ctypes.Structure):
    _fields_=[('low',DWORD),('high',DWORD)]
    def ticks(self):return (self.high<<32)|self.low

class ProcessMemory(ctypes.Structure):
    _fields_=[('cb',DWORD),('faults',DWORD)]+[(k,SIZE) for k in (
        'peakWorkingSet','workingSet','peakPagedPool','pagedPool','peakNonPagedPool',
        'nonPagedPool','pagefile','peakPagefile','private')]

class Performance(ctypes.Structure):
    _fields_=[('cb',DWORD)]+[(k,SIZE) for k in ('commitTotal','commitLimit','commitPeak',
        'physicalTotal','physicalAvailable','systemCache','kernelTotal','kernelPaged',
        'kernelNonPaged','pageSize')]+[(k,DWORD) for k in ('handles','processCount','threadCount')]

class Api:
    def __init__(self):
        if os.name!='nt':raise OSError('Windows required')
        self.k=ctypes.WinDLL('kernel32',use_last_error=True)
        signatures={
            'OpenProcess':([DWORD,BOOL,DWORD],HANDLE),
            'CloseHandle':([HANDLE],BOOL),
            'QueryFullProcessImageNameW':([HANDLE,DWORD,ctypes.c_wchar_p,ctypes.POINTER(DWORD)],BOOL),
            'GetProcessTimes':([HANDLE,*([ctypes.POINTER(FileTime)]*4)],BOOL),
            'K32EnumProcesses':([ctypes.POINTER(DWORD),DWORD,ctypes.POINTER(DWORD)],BOOL),
            'K32GetProcessMemoryInfo':([HANDLE,ctypes.POINTER(ProcessMemory),DWORD],BOOL),
            'K32GetPerformanceInfo':([ctypes.POINTER(Performance),DWORD],BOOL),
            'K32EmptyWorkingSet':([HANDLE],BOOL),
            'TerminateProcess':([HANDLE,DWORD],BOOL),
            'WaitForSingleObject':([HANDLE,DWORD],DWORD),
        }
        for name,(args,result) in signatures.items():
            f=getattr(self.k,name);f.argtypes=args;f.restype=result

    def checked(self,value):
        if not value:raise ctypes.WinError(ctypes.get_last_error())
        return value

    def process(self,pid,handle=None):
        h=handle or self.checked(self.k.OpenProcess(0x1000,False,pid))
        try:
            name=ctypes.create_unicode_buffer(32768);length=DWORD(len(name))
            self.checked(self.k.QueryFullProcessImageNameW(h,0,name,ctypes.byref(length)))
            times=[FileTime() for _ in range(4)]
            self.checked(self.k.GetProcessTimes(h,*[ctypes.byref(t) for t in times]))
            m=ProcessMemory();m.cb=ctypes.sizeof(m)
            self.checked(self.k.K32GetProcessMemoryInfo(h,ctypes.byref(m),m.cb))
            return dict(pid=pid,name=PureWindowsPath(name.value).name,creationTicks=str(times[0].ticks()),
                        cpuTicks=str(times[2].ticks()+times[3].ticks()),workingSetMiB=round(m.workingSet/MiB,2),
                        privateMiB=round(m.private/MiB,2))
        finally:
            if handle is None:self.k.CloseHandle(h)

    def performance(self):
        p=Performance();p.cb=ctypes.sizeof(p)
        self.checked(self.k.K32GetPerformanceInfo(ctypes.byref(p),p.cb))
        result={k+'MiB':round(getattr(p,k)*p.pageSize/MiB,2) for k in (
            'physicalTotal','physicalAvailable','commitTotal','commitLimit','systemCache','kernelPaged','kernelNonPaged')}
        result['processCount']=p.processCount
        return result

    def screensavers(self):
        ids=(DWORD*16384)();used=DWORD();self.checked(self.k.K32EnumProcesses(ids,ctypes.sizeof(ids),ctypes.byref(used)))
        result=[]
        for pid in ids[:used.value//ctypes.sizeof(DWORD)]:
            if not pid:continue
            try:
                row=self.process(pid)
                if row['name'].lower()=='360huabao.scr':result.append(row)
            except OSError:pass
        return result[:16]

    def _terminate_handle(self,handle):
        self.checked(self.k.TerminateProcess(handle,0))
        if self.k.WaitForSingleObject(handle,3000)!=0:raise TimeoutError('Screensaver exit not confirmed')

    def stop_screensaver_checked(self,expected,signatures):
        # QUERY_LIMITED_INFORMATION | TERMINATE | SYNCHRONIZE; retain this exact
        # kernel handle throughout verification and termination to avoid PID reuse.
        handle=self.checked(self.k.OpenProcess(0x101001,False,expected['pid']))
        try:
            row=self.process(expected['pid'],handle)
            if any(row[k]!=expected[k] for k in ('pid','name','creationTicks')):raise ValueError('Process identity changed')
            if row['name'].lower()!='360huabao.scr':raise ValueError('Not the authorized screensaver')
            name=ctypes.create_unicode_buffer(32768);length=DWORD(len(name))
            self.checked(self.k.QueryFullProcessImageNameW(handle,0,name,ctypes.byref(length)))
            path=name.value
            if path not in signatures:signatures[path]=image_signature(path)
            signature=signatures[path]
            if not approved_screensaver(path,signature):
                raise ValueError('Screensaver publisher not verified: '+signature.get('status','')+' / '+signature.get('subject',''))
            self._terminate_handle(handle)
            return dict(pid=row['pid'],name=row['name'],creationTicks=row['creationTicks'],
                        workingSetMiB=row['workingSetMiB'],privateMiB=row['privateMiB'],exitConfirmed=True)
        finally:self.k.CloseHandle(handle)

    def snapshot(self):
        started=time.monotonic();ids=(DWORD*16384)();used=DWORD()
        self.checked(self.k.K32EnumProcesses(ids,ctypes.sizeof(ids),ctypes.byref(used)))
        rows=[];unreadable=0
        for pid in ids[:used.value//ctypes.sizeof(DWORD)]:
            if not pid:continue
            try:rows.append(self.process(pid))
            except OSError:unreadable+=1
        groups={}
        for row in rows:
            group=groups.setdefault(row['name'].lower(),dict(name=row['name'],count=0,workingSetMiB=0,privateMiB=0))
            group['count']+=1
            for key in ('workingSetMiB','privateMiB'):group[key]=round(group[key]+row[key],2)
        return dict(at=time.time(),**self.performance(),processes=sorted(rows,key=lambda r:r['workingSetMiB'],reverse=True)[:20],
                    groups=sorted(groups.values(),key=lambda r:r['privateMiB'],reverse=True)[:10],
                    screensavers=[r for r in rows if r['name'].lower()=='360huabao.scr'][:16],
                    unreadableProcesses=unreadable,truncated=used.value==ctypes.sizeof(ids),seconds=round(time.monotonic()-started,3))

    def trim_checked(self,expected):
        h=self.checked(self.k.OpenProcess(0x1100,False,expected['pid']))
        try:
            before=self.process(expected['pid'],h)
            if any(before[k]!=expected[k] for k in ('pid','name','creationTicks')):raise ValueError('Process identity changed')
            self.checked(self.k.K32EmptyWorkingSet(h))
            after=self.process(expected['pid'],h)
            return dict(pid=before['pid'],name=before['name'],beforeWorkingSetMiB=before['workingSetMiB'],
                        afterWorkingSetMiB=after['workingSetMiB'],privateBeforeMiB=before['privateMiB'],privateAfterMiB=after['privateMiB'])
        finally:self.k.CloseHandle(h)

def eligible(before,after):
    return (after['name'].lower() in ALLOWED and after['workingSetMiB']>=128
            and all(before[k]==after[k] for k in ('pid','name','creationTicks'))
            and 0<=int(after['cpuTicks'])-int(before['cpuTicks'])<=500000)

def send(config,report):
    raw=json.dumps(report,ensure_ascii=False).encode()
    if len(raw)>16384:raise ValueError('Diagnostic too large')
    req=urllib.request.Request(config['url']+'/node/report',data=raw,
        headers={'Authorization':'Bearer '+config['token'],'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=15) as r:
        if r.status!=200:raise ValueError('Diagnostic upload rejected')

def main():
    if sys.argv[1:]==['--local-json']:
        # Explicit read-only diagnostics: no configuration, upload, file writes or cleanup.
        print(json.dumps(Api().snapshot(),ensure_ascii=True))
        return
    root=Path(sys.argv[1]);config=json.loads((root/'config.json').read_text(encoding='utf-8-sig'))
    trim=sys.argv[2:]==['--trim-idle']
    stop_screensaver=sys.argv[2:]==['--stop-screensaver']
    if sys.argv[2:] and not (trim or stop_screensaver):raise ValueError('Unsupported memory action')
    api=Api();before=api.snapshot()
    report=dict(kind='memory-v1',phase='before',host=os.environ.get('COMPUTERNAME'),before=before,trimmed=[],skipped=[])
    # Establish a remote baseline before any reclamation.
    send(config,report)
    if stop_screensaver and before['physicalAvailableMiB']<2560:
        signatures={};report['stoppedScreensavers']=[]
        for candidate in api.screensavers():
            try:report['stoppedScreensavers'].append(api.stop_screensaver_checked(candidate,signatures))
            except (OSError,ValueError,TimeoutError,subprocess.SubprocessError) as e:
                report['skipped'].append(dict(pid=candidate['pid'],reason=str(e)[:220]))
        time.sleep(5)
    if trim and before['physicalAvailableMiB']<2560:
        time.sleep(1)
        for old in before['processes']:
            if len(report['trimmed'])>=6 or api.performance()['physicalAvailableMiB']>=2560:break
            if old['name'].lower() not in ALLOWED:continue
            try:
                current=api.process(old['pid'])
                if eligible(old,current):report['trimmed'].append(api.trim_checked(current))
            except (OSError,ValueError) as e:report['skipped'].append(dict(pid=old['pid'],reason=type(e).__name__))
        time.sleep(5)
    report.update(phase='after',after=api.snapshot(),trimRequested=trim,screensaverStopRequested=stop_screensaver)
    path=Path(config['data'])/'memory-diagnostic.json';path.write_text(json.dumps(report,ensure_ascii=False),encoding='utf-8')
    send(config,report)
    print(json.dumps(dict(sent=True,trimmed=len(report['trimmed']),availableMiB=report['after']['physicalAvailableMiB'])))

if __name__=='__main__':main()
