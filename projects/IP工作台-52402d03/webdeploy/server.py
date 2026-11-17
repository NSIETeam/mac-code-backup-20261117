import os
import uvicorn
from operations.serve import create_app
os.environ['IP_ALLOW_CODEX']='0'
app=create_app(os.environ['IP_DATA_DIR'],require_team_auth=True,
               allowed_hosts=[os.environ['IP_PUBLIC_HOST']],station_backed=True)
if __name__=='__main__':
    uvicorn.run(app,host='127.0.0.1',port=18767,proxy_headers=True,
                forwarded_allow_ips='127.0.0.1',access_log=False)
