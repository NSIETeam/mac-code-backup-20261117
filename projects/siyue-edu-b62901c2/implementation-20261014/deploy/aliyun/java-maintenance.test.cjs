'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const text=fs.readFileSync(path.join(__dirname,'java-maintenance.nginx.conf'),'utf8');
const config=text.replace(/^\s*#.*$/gm,'');
test('migration freeze closes both API root and every version without an upstream fallback',()=>{
 assert.match(config,/location = \/crm-api \{/);
 assert.match(config,/location \^~ \/crm-api\/ \{/);
 assert.equal((config.match(/return 503 /g)||[]).length,2);
 assert.doesNotMatch(config,/proxy_pass|fastcgi_pass|rewrite|limit_except|request_method/);
});
test('maintenance responses cannot be cached and instruct clients to retain uncertain submissions',()=>{
 assert.equal((config.match(/Cache-Control "no-store" always/g)||[]).length,2);
 assert.equal((config.match(/Retry-After "60" always/g)||[]).length,2);
 for(const [,body] of config.matchAll(/return 503 '([^']+)'/g)){
  const reply=JSON.parse(body);assert.equal(reply.code,'MIGRATION_MAINTENANCE');
  assert.match(reply.message,/保留原请求/);
 }
});
