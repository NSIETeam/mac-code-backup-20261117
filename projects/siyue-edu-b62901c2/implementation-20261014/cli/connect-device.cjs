'use strict';
const auth=require('./auth-client.cjs'),path=require('node:path'),os=require('node:os');
async function connect({server='https://www.siyuejn.com/crm-api/',name='思跃 · 本机命令行',env=process.env,onPair=console.log,signal}={}){
 env={...env,SIYUE_CONFIG_DIR:env.SIYUE_DEVICE_CONFIG_DIR||path.join(os.homedir(),'.config','siyue-device-cli')};
 server=auth.serverURL(server);if(new URL(server).protocol!=='https:')throw Error('设备连接必须使用 HTTPS');
 const pair=await auth.request(server,'v1/auth/cli-pair/start',{method:'POST',body:{name}});
 onPair({verificationUri:pair.verificationUri,userCode:pair.userCode,expiresIn:pair.expiresIn});
 const until=Date.now()+pair.expiresIn*1000;
 while(Date.now()<until){if(signal?.aborted)throw Error('连接已取消');const result=await auth.request(server,'v1/auth/cli-pair/poll',{method:'POST',body:{deviceCode:pair.deviceCode}});if(result.status==='connected'){auth.writeSession({server,token:result.token,user:result.user,expiresAt:null,persistent:true,connectionId:result.connectionId,apiVersion:'v1'},env);return {connected:true,account:result.user.name,connectionId:result.connectionId};}await new Promise(resolve=>setTimeout(resolve,2000));}
 throw Error('批准等待已过期，请重新发起连接');
}
if(require.main===module)connect({name:process.argv[2]||'思跃 · 本机命令行'}).then(result=>console.log(JSON.stringify(result))).catch(error=>{console.error(error.message);process.exitCode=1;});
module.exports={connect};
