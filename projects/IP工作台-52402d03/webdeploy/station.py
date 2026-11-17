"""Outbound HTTPS relay for the existing local scheduler; does not create a collector loop."""
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import requests
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from operations import collection_policy, heartbeat, workflow_engine
from operations.core import Store
from operations.web_station import FEED_KINDS

def command(store, item):
    node, action, data = item['node'], item['action'], item['data']
    if action=='xhs_draft':
        from operations.creator_draft import run
        return run(data['payload'],data['target'])
    if action=='save_focus':
        from operations.monitoring import save
        return save(store,data)
    provider = heartbeat.NODES[node]
    if action=='capture_query':
        from operations.capture import station_start
        return station_start(store,node,data['keyword'])
    if action == 'original_video' and node=='xhs':
        from operations import original_video
        ident=data['id']
        record=original_video.save(store,ident)
        path=original_video.directory(store)/(record['sha256']+'.mp4')
        endpoint=os.environ['IP_STATION_URL'].rsplit('/',1)[0]+'/videos/'+ident
        headers={'Authorization':'Bearer '+Path(os.environ['IP_STATION_TOKEN_FILE']).read_text().strip(),
                 'X-Video-Identity':record['identity'],'X-Video-Sha256':record['sha256']}
        with path.open('rb') as file:
            reply=requests.put(endpoint,data=file,headers=headers,timeout=(10,180),allow_redirects=False)
        if reply.status_code!=200:raise ValueError('采集电脑已保留原片，云端保存失败，请稍后再试')
        return reply.json()
    if action == 'login' and node=='xhs':
        from operations.social_mcp import login_qr
        return login_qr()
    if action == 'media_config' and set(data)=={'enabled'} and type(data['enabled']) is bool:
        return store.put_many('media_config',[{'id':'main','enabled':data['enabled']}])[0]
    if action == 'media':
        from operations import media_text
        ident = data['id']
        if data.get('retry'):media_text.retry(store,ident)
        else:media_text.enqueue(store,ident,1)
        return media_text.summary(store,ident)
    if action == 'resume':
        return collection_policy.resume(store, provider)
    if action == 'check':
        if node == 'channels':
            from operations.channels_connection import diagnose
            result = diagnose()
        elif node == 'xhs':
            from operations.social_mcp import connection_status
            result = connection_status('xiaohongshu')
        else:
            from operations.douyin_local import connection_status
            result = connection_status()
        return {**result, 'policy':collection_policy.summary(store, provider)}
    if action not in ('heartbeat','collect') or data['config'].get('provider') != provider:
        raise ValueError('只接受该节点的本地采集设置')
    graph = workflow_engine.get_graph(store)
    config = next(n['config'] for n in graph['nodes'] if n['id']==node)
    keyword = data['config'].get('keyword','')
    if keyword != config.get('keyword'):
        config['keyword'] = keyword
        graph = workflow_engine.save_graph(store, {**graph, 'expected_version':graph['version']})
    if action == 'heartbeat':
        return heartbeat.configure(store,node,data['options'])
    return workflow_engine.execute(store,node,graph['version'])

def main():
    store = Store(Path(os.environ['IP_STATION_DB']))
    url = os.environ['IP_STATION_URL']
    if not url.startswith('https://'):
        raise ValueError('需使用 HTTPS')
    client = requests.Session()
    client.headers['Authorization'] = 'Bearer '+Path(os.environ['IP_STATION_TOKEN_FILE']).read_text().strip()
    sent, results = {}, []
    next_scan = 0
    todo = []
    states = []
    while True:
        try:
            if not todo and time.monotonic() >= next_scan:
                next_scan = time.monotonic()+15
                with store.db() as con:
                    for kind in sorted(FEED_KINDS):
                        for ident, raw in con.execute('SELECT id,body FROM records WHERE kind=?',(kind,)):
                            digest=hashlib.sha256(raw.encode()).digest()
                            if sent.get((kind,ident))!=digest:
                                todo.append((kind,ident,digest,raw))
                states=[heartbeat.overview(store,n) for n in heartbeat.NODES]
            batch=[]; size=0
            for row in todo[:100]:
                if batch and size+len(row[3].encode())>1400000:break
                batch.append(row);size+=len(row[3].encode())
            payload={'rows':[[k,json.loads(raw)] for k,_,_,raw in batch], 'nodes':states, 'results':results}
            reply=client.post(url,json=payload,timeout=(8,15),allow_redirects=False)
            reply.raise_for_status()
            msg=reply.json()
            if msg['received']!=len(batch):raise ValueError('采集同步数量不符')
            sent.update({(k,i):d for k,i,d,_ in batch});del todo[:len(batch)]
            results=[]
            for item in msg['commands']:
                try:results.append({'id':item['id'],'value':command(store,item)})
                except Exception as e:results.append({'id':item['id'],'error':str(e)[:160] if isinstance(e,ValueError) else '本机操作失败，请检查采集站'})
            time.sleep(.1 if todo or results else 3)
        except Exception as error:
            print(type(error).__name__,flush=True);time.sleep(8)
if __name__=='__main__':main()
