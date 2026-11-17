'use strict';

const crypto = require('node:crypto');
const http = require('node:http');
const { spawn } = require('node:child_process');
const auth = require('./auth-client.cjs');
const java = require('./java-query-client.cjs');

const failure = (code, message) => Object.assign(new Error(message), { code });
const base64url = value => Buffer.from(value).toString('base64url');
const secureUrl = value => {
  try {
    const url = new URL(value);
    return !url.username && !url.password && !url.hash && (url.protocol === 'https:' || url.protocol === 'http:'
      && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname));
  } catch { return false; }
};

async function json(fetcher, url, options = {}) {
  const response = await fetcher(url, { redirect: 'error', cache: 'no-store', signal: AbortSignal.timeout(15000),
    headers: { Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {}) },
    ...options });
  let body;
  try { body = await response.json(); } catch { throw failure('OIDC_INVALID_RESPONSE', '身份服务未返回有效 JSON'); }
  if (!response.ok) throw failure('OIDC_REQUEST_FAILED', '身份服务拒绝了登录请求');
  return body;
}

function launchBrowser(url, platform = process.platform) {
  const command = platform === 'darwin' ? 'open' : platform === 'win32' ? 'explorer.exe' : 'xdg-open';
  return new Promise((resolve, reject) => {
    let child;
    try { child = spawn(command, [url], { detached: true, stdio: 'ignore', shell: false }); }
    catch { reject(failure('BROWSER_UNAVAILABLE', '无法启动浏览器；请确认系统已配置浏览器')); return; }
    child.once('error', () => reject(failure('BROWSER_UNAVAILABLE', '无法启动浏览器；请确认系统已配置浏览器')));
    child.once('spawn', () => { child.unref(); resolve(); });
  });
}

function jwtClaims(token) {
  if (typeof token !== 'string' || token.length > 16384 || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(token))
    throw failure('OIDC_INVALID_TOKEN', '身份服务返回的 JWT 无效');
  try {
    const [headerPart, payloadPart, signaturePart] = token.split('.');
    const header = JSON.parse(Buffer.from(headerPart, 'base64url').toString('utf8'));
    const claims = JSON.parse(Buffer.from(payloadPart, 'base64url').toString('utf8'));
    if (!header || typeof header !== 'object' || Array.isArray(header)
      || !claims || typeof claims !== 'object' || Array.isArray(claims)) throw new Error('invalid');
    return { header, claims, signingInput: Buffer.from(`${headerPart}.${payloadPart}`),
      signature: Buffer.from(signaturePart, 'base64url') };
  }
  catch { throw failure('OIDC_INVALID_TOKEN', '身份服务返回的 JWT 内容无效'); }
}

function ecdsaJoseToDer(signature) {
  const width = signature.length / 2;
  if (!Number.isInteger(width) || ![32, 48].includes(width)) throw failure('OIDC_INVALID_TOKEN', 'ECDSA 签名长度无效');
  const integer = value => {
    let start = 0;
    while (start < value.length - 1 && value[start] === 0) start++;
    let trimmed = value.subarray(start);
    if (trimmed[0] & 0x80) trimmed = Buffer.concat([Buffer.from([0]), trimmed]);
    return Buffer.concat([Buffer.from([0x02, trimmed.length]), trimmed]);
  };
  const body = Buffer.concat([integer(signature.subarray(0, width)), integer(signature.subarray(width))]);
  return Buffer.concat([Buffer.from([0x30, body.length]), body]);
}

function verifyJwtSignature(jwt, jwks) {
  const { header, claims, signingInput, signature } = jwt;
  if (!header.kid || typeof header.kid !== 'string' || !Array.isArray(jwks?.keys))
    throw failure('OIDC_INVALID_TOKEN', 'OIDC 令牌缺少签名密钥编号');
  const jwk = jwks.keys.find(key => key.kid === header.kid && (!key.use || key.use === 'sig')
    && (!key.alg || key.alg === header.alg));
  if (!jwk) throw failure('OIDC_INVALID_TOKEN', 'OIDC 签名密钥不存在');
  let key, algorithm, bytes = signature, options = undefined;
  try { key = crypto.createPublicKey({ key: jwk, format: 'jwk' }); }
  catch { throw failure('OIDC_INVALID_TOKEN', 'OIDC 公钥格式无效'); }
  if (header.alg === 'RS256') algorithm = 'RSA-SHA256';
  else if (header.alg === 'RS384') algorithm = 'RSA-SHA384';
  else if (header.alg === 'RS512') algorithm = 'RSA-SHA512';
  else if (['PS256', 'PS384', 'PS512'].includes(header.alg)) {
    algorithm = header.alg === 'PS256' ? 'sha256' : header.alg === 'PS384' ? 'sha384' : 'sha512';
    options = { key, padding: crypto.constants.RSA_PKCS1_PSS_PADDING,
      saltLength: header.alg === 'PS256' ? 32 : header.alg === 'PS384' ? 48 : 64 };
  } else if (header.alg === 'ES256' || header.alg === 'ES384') {
    algorithm = header.alg === 'ES256' ? 'sha256' : 'sha384';
    bytes = ecdsaJoseToDer(signature);
  } else if (header.alg === 'EdDSA') algorithm = null;
  else throw failure('OIDC_INVALID_TOKEN', 'OIDC 签名算法不受支持');
  const valid = crypto.verify(algorithm, signingInput, options || key, bytes);
  if (!valid) throw failure('OIDC_INVALID_TOKEN', 'OIDC 令牌签名校验失败');
  return claims;
}

function validClaims(claims, issuer, audience, now) {
  const aud = Array.isArray(claims.aud) ? claims.aud.includes(audience) : claims.aud === audience;
  return claims.iss === issuer && aud && Number.isFinite(Number(claims.exp)) && Number(claims.exp) > now;
}

function callbackCode(server, redirectUri, expectedState, timeoutMs) {
  let cancel;
  const promise = new Promise((resolve, reject) => {
    let settled = false;
    const finish = (error, code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const result = code;
      server.close(() => error ? reject(error) : resolve(result));
    };
    cancel = error => finish(error);
    const timer = setTimeout(() => finish(failure('OIDC_CALLBACK_TIMEOUT', '等待身份服务登录回调超时')), timeoutMs);
    server.on('request', (request, response) => {
      const url = new URL(request.url || '/', redirectUri);
      if (request.method !== 'GET' || url.pathname !== '/callback') {
        response.writeHead(404, { 'Cache-Control': 'no-store' }); response.end(); return;
      }
      const states = url.searchParams.getAll('state'), codes = url.searchParams.getAll('code');
      const actual = Buffer.from(states[0] || '');
      const expected = Buffer.from(expectedState);
      const matches = states.length === 1 && actual.length === expected.length
        && crypto.timingSafeEqual(actual, expected);
      const error = url.searchParams.get('error');
      response.writeHead(matches && codes.length === 1 && codes[0] && !error ? 200 : 400, {
        'Cache-Control': 'no-store', 'Pragma': 'no-cache', 'Content-Type': 'text/html; charset=utf-8',
        'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff',
      });
      response.end(matches && codes.length === 1 && codes[0] && !error
        ? '<!doctype html><meta charset="utf-8"><title>登录完成</title><p>登录完成，请返回思跃命令行。</p>'
        : '<!doctype html><meta charset="utf-8"><title>登录失败</title><p>登录未完成，请返回思跃命令行重新尝试。</p>');
      if (!matches) return;
      if (error || codes.length !== 1 || !codes[0]) return finish(failure('OIDC_AUTHORIZATION_FAILED', '身份服务未完成登录'));
      finish(null, codes[0]);
    });
  });
  promise.catch(() => {});
  return { promise, cancel };
}

async function login(server, { env = process.env, fetcher = globalThis.fetch, openBrowser = launchBrowser,
  timeoutMs = 180000 } = {}) {
  const base = auth.serverURL(server);
  const configEnvelope = await json(fetcher, new URL('public/cli-oidc-config', base));
  const config = configEnvelope?.data;
  if (configEnvelope?.code !== 'OK' || !config || typeof config.issuer !== 'string'
    || typeof config.clientId !== 'string' || typeof config.apiAudience !== 'string'
    || typeof config.scope !== 'string' || !config.issuer.trim() || !config.clientId.trim()
    || !config.apiAudience.trim() || !/^openid(?: [A-Za-z0-9:_-]+)*$/.test(config.scope))
    throw failure('OIDC_CLI_NOT_CONFIGURED', '服务器未配置 Java CLI OIDC 公共客户端');
  const issuer = new URL(config.issuer);
  if (!secureUrl(issuer.href) || issuer.search)
    throw failure('OIDC_INVALID_CONFIG', 'OIDC 签发者地址无效');
  const discoveryUrl = new URL('.well-known/openid-configuration', issuer.href.endsWith('/') ? issuer : `${issuer.href}/`);
  const metadata = await json(fetcher, discoveryUrl);
  if (metadata.issuer !== config.issuer || !secureUrl(metadata.authorization_endpoint)
    || !secureUrl(metadata.token_endpoint) || !secureUrl(metadata.jwks_uri))
    throw failure('OIDC_INVALID_DISCOVERY', 'OIDC 服务发现信息无效');

  const state = base64url(crypto.randomBytes(32));
  const nonce = base64url(crypto.randomBytes(32));
  const verifier = base64url(crypto.randomBytes(48));
  const challenge = base64url(crypto.createHash('sha256').update(verifier).digest());
  const serverHandle = http.createServer();
  await new Promise((resolve, reject) => {
    serverHandle.once('error', reject);
    serverHandle.listen(0, '127.0.0.1', resolve);
  });
  const address = serverHandle.address();
  if (!address || typeof address === 'string') { serverHandle.close(); throw failure('OIDC_CALLBACK_FAILED', '无法建立本机登录回调'); }
  const redirectUri = `http://127.0.0.1:${address.port}/callback`;
  const authorization = new URL(metadata.authorization_endpoint);
  for (const [key, value] of Object.entries({ client_id: config.clientId, redirect_uri: redirectUri,
    response_type: 'code', response_mode: 'query', scope: config.scope, state, nonce,
    code_challenge: challenge, code_challenge_method: 'S256' })) authorization.searchParams.set(key, value);
  const callback = callbackCode(serverHandle, redirectUri, state, timeoutMs);
  try {
    await openBrowser(authorization.href);
    const code = await callback.promise;
    const form = new URLSearchParams({ grant_type: 'authorization_code', client_id: config.clientId, code,
      redirect_uri: redirectUri, code_verifier: verifier });
    const tokens = await json(fetcher, metadata.token_endpoint, { method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' }, body: form.toString() });
    const now = Math.floor(Date.now() / 1000), expiresIn = Number(tokens.expires_in);
    if (tokens.token_type?.toLowerCase() !== 'bearer' || !Number.isFinite(expiresIn) || expiresIn <= 0
      || expiresIn > 86400 || typeof tokens.access_token !== 'string' || typeof tokens.id_token !== 'string')
      throw failure('OIDC_INVALID_TOKEN', '身份服务返回的令牌无效');
    const jwks = await json(fetcher, metadata.jwks_uri);
    const access = verifyJwtSignature(jwtClaims(tokens.access_token), jwks);
    const identity = verifyJwtSignature(jwtClaims(tokens.id_token), jwks);
    if (!validClaims(access, config.issuer, config.apiAudience, now)
      || !validClaims(identity, config.issuer, config.clientId, now) || identity.nonce !== nonce
      || typeof identity.sub !== 'string' || !identity.sub)
      throw failure('OIDC_INVALID_TOKEN', '身份令牌签发者、受众、有效期或随机数校验失败');
    const user = await java.authenticate(base, tokens.access_token, env, fetcher);
    return user;
  } catch (error) {
    callback.cancel(error);
    if (serverHandle.listening) serverHandle.close();
    throw error;
  }
}

module.exports = { login, launchBrowser, jwtClaims, verifyJwtSignature, validClaims, callbackCode };
