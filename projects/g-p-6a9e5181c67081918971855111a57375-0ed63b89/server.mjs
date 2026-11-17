import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('.', import.meta.url));
const port = Number(process.env.PORT || 4173);
const mime = { '.html':'text/html; charset=utf-8', '.css':'text/css; charset=utf-8', '.js':'text/javascript; charset=utf-8', '.json':'application/json; charset=utf-8', '.svg':'image/svg+xml', '.png':'image/png', '.jpg':'image/jpeg' };
const provider = process.env.AI_PROVIDER || 'openai-compatible';
const apiUrl = process.env.AI_API_URL || 'https://api.openai.com/v1/chat/completions';
const apiKey = process.env.AI_API_KEY || '';
const model = process.env.AI_MODEL || (provider === 'anthropic' ? 'claude-3-5-sonnet-latest' : 'gpt-4o-mini');

async function generate(body) {
  if (!apiKey) return { configured:false, error:'AI_API_KEY is not configured' };
  const messages = [
    { role:'system', content:'你是文案猫电商工作台的内容助手。请用简洁、可执行的中文回答电商主图、详情页、小红书、短视频、薪酬考核和管理问题。' },
    { role:'user', content:body.prompt }
  ];
  const headers = { 'Content-Type':'application/json', Authorization:`Bearer ${apiKey}` };
  const response = await fetch(apiUrl, { method:'POST', headers, body:JSON.stringify({ model:body.model || model, messages, temperature:0.7 }) });
  if (!response.ok) throw new Error(`AI provider returned ${response.status}`);
  const data = await response.json();
  const text = data.choices?.[0]?.message?.content || data.content?.[0]?.text || '';
  if (!text) throw new Error('AI provider returned an empty result');
  return { configured:true, result:text, model:body.model || model };
}

createServer(async (request, response) => {
  try {
    if (request.method === 'GET' && request.url === '/api/status') {
      response.writeHead(200, {'Content-Type':'application/json; charset=utf-8'});
      response.end(JSON.stringify({ brand:'文案猫', provider, configured:Boolean(apiKey), model })); return;
    }
    if (request.method === 'POST' && request.url === '/api/generate') {
      let raw=''; for await (const chunk of request) raw += chunk;
      const body=JSON.parse(raw || '{}');
      const result=await generate(body);
      response.writeHead(result.configured ? 200 : 503, {'Content-Type':'application/json; charset=utf-8'});
      response.end(JSON.stringify(result)); return;
    }
    const requestPath = decodeURIComponent((request.url || '/').split('?')[0]);
    const safePath = normalize(requestPath === '/' ? '/index.html' : requestPath);
    if (safePath.includes('..')) { response.writeHead(403); response.end('Forbidden'); return; }
    const content = await readFile(join(root, safePath));
    response.writeHead(200, {'Content-Type':mime[extname(safePath)] || 'application/octet-stream', 'Cache-Control':'no-cache'});
    response.end(content);
  } catch (error) {
    response.writeHead(error.code === 'ENOENT' ? 404 : 500, {'Content-Type':'text/plain; charset=utf-8'});
    response.end(error.code === 'ENOENT' ? 'Not found' : 'Server error');
  }
}).listen(port, '127.0.0.1', () => console.log(`文案猫本地服务已启动：http://127.0.0.1:${port}`));
