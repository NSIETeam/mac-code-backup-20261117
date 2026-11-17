#!/usr/bin/env python3
"""Pin the WSL host key obtained through the authenticated printing-agent report."""
import json,pathlib,re,urllib.request,time
ROOT=pathlib.Path('/opt/siyue-auxiliary/pilot-20261116')
def main():
 c=json.loads((ROOT/'admin.json').read_text())
 url='http://127.0.0.1:5082/api/admin/devices/e54513ad-6044-4555-9eaf-3d8624d69a02/operations'
 req=urllib.request.Request(url,headers={'Authorization':'Bearer '+c['token']})
 with urllib.request.urlopen(req,timeout=15) as response:ops=json.load(response)
 eligible=[o for o in ops if o['action']=='diagnose' and o['state']=='succeeded']
 if not eligible:raise ValueError('No authenticated diagnostic')
 r=eligible[0]['result'];b=r.get('auxiliary',{}).get('workstation',{}).get('bootstrap',{})
 if b.get('state')!='ready':raise ValueError('Workstation bootstrap not ready')
 if r.get('machine')!='SIYUE12B001':raise ValueError('Diagnostic host mismatch')
 host=b.get('hostKey','').split()
 if len(host)<2 or host[0]!='ssh-ed25519' or not re.fullmatch('[A-Za-z0-9+/=]+',host[1]):raise ValueError('Invalid host key')
 key='[127.0.0.1]:5399 '+host[0]+' '+host[1]+'\n'
 file=ROOT/'workstation-access/workstation_known_hosts'
 if file.exists() and file.read_text()!=key:raise ValueError('Workstation host key changed; inspect before replacing')
 file.write_text(key);file.chmod(0o600)
 (ROOT/'workstation-bootstrap-verified.json').write_text(json.dumps(dict(capturedAt=r['capturedAt'],bootstrap=b),ensure_ascii=False))
 print(json.dumps(dict(pinned=True,state=b['state'],kernel=b.get('kernel'),podman=b.get('podman'))))
if __name__=='__main__':main()
