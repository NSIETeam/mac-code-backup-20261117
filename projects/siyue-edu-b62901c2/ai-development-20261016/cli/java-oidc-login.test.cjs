'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const auth = require('./auth-client.cjs');
const oidc = require('./java-oidc-login.cjs');

const b64 = value => Buffer.from(JSON.stringify(value)).toString('base64url');
function signedToken(claims, key, kid = 'test-key') {
  const header = b64({ alg: 'RS256', kid, typ: 'JWT' });
  const payload = b64(claims);
  const signingInput = `${header}.${payload}`;
  return `${signingInput}.${crypto.sign('RSA-SHA256', Buffer.from(signingInput), key).toString('base64url')}`;
}
async function listen(server) {
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return `http://127.0.0.1:${server.address().port}`;
}

test('Java CLI login completes native PKCE, validates signed OIDC tokens, and stores only a private session', async t => {
  const keys = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const publicJwk = keys.publicKey.export({ format: 'jwk' });
  const jwk = { ...publicJwk, kid: 'test-key', alg: 'RS256', use: 'sig' };
  const now = Math.floor(Date.now() / 1000), received = [];
  let issuer;
  const provider = http.createServer(async (request, response) => {
    const url = new URL(request.url, issuer);
    if (url.pathname.endsWith('/.well-known/openid-configuration')) return send(response, {
      issuer, authorization_endpoint: `${issuer}/authorize`, token_endpoint: `${issuer}/token`, jwks_uri: `${issuer}/jwks`
    });
    if (url.pathname === '/jwks') return send(response, { keys: [jwk] });
    if (url.pathname === '/token') {
      let body = '';
      for await (const chunk of request) body += chunk;
      const form = new URLSearchParams(body);
      const authorization = received.at(-1);
      const challenge = crypto.createHash('sha256').update(form.get('code_verifier')).digest('base64url');
      assert.equal(form.get('code'), 'ONE_TIME_CODE', body);
      assert.equal(form.get('client_id'), 'siyue-cli');
      assert.equal(form.get('redirect_uri'), authorization.redirect_uri);
      assert.equal(challenge, authorization.code_challenge);
      const accessToken = signedToken({ iss: issuer, aud: 'siyue-core', sub: 'oidc-subject', exp: now + 600 }, keys.privateKey);
      const idToken = signedToken({ iss: issuer, aud: 'siyue-cli', sub: 'oidc-subject',
        nonce: authorization.nonce, exp: now + 600 }, keys.privateKey);
      return send(response, { token_type: 'Bearer', expires_in: 600, access_token: accessToken, id_token: idToken });
    }
    response.writeHead(404); response.end();
  });
  function send(response, body) {
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(body));
  }
  issuer = await listen(provider);
  t.after(() => provider.close());

  const api = http.createServer(async (request, response) => {
    if (request.url === '/crm-api/v2/public/cli-oidc-config') return send(response, {
      code: 'OK', data: { issuer, clientId: 'siyue-cli', apiAudience: 'siyue-core', scope: 'openid profile' }
    });
    if (request.url === '/crm-api/v2/identity/me') {
      assert.match(request.headers.authorization, /^Bearer /);
      return send(response, { code: 'OK', data: { principalId: 'EMP_1', permissions: ['crm.customer.read'] } });
    }
    response.writeHead(404); response.end();
  });
  const apiBase = `${await listen(api)}/crm-api/v2/`;
  t.after(() => api.close());
  const root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'siyue-cli-oidc-')));
  const env = { SIYUE_CONFIG_DIR: path.join(root, 'config') };
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));

  const identity = await oidc.login(apiBase, {
    env,
    fetcher: (...args) => fetch(...args),
    openBrowser: async href => {
      const authorization = new URL(href);
      const details = Object.fromEntries(authorization.searchParams);
      assert.equal(details.response_type, 'code');
      assert.equal(details.code_challenge_method, 'S256');
      assert.match(details.code_challenge, /^[A-Za-z0-9_-]{43}$/);
      received.push(details);
      const callback = new URL(details.redirect_uri);
      callback.searchParams.set('code', 'ONE_TIME_CODE');
      callback.searchParams.set('state', details.state);
      const response = await fetch(callback, { redirect: 'error' });
      assert.equal(response.status, 200);
      assert.match(await response.text(), /登录完成/);
    },
  });
  assert.equal(identity.principalId, 'EMP_1');
  const session = auth.readSession(env);
  assert.equal(session.apiVersion, 'v2');
  assert.equal(session.principalId, 'EMP_1');
  assert.equal(fs.statSync(path.join(auth.configDirectory(env), 'session.json')).mode & 0o777, 0o600);
});

test('Java CLI callback ignores forged state and accepts only the expected state', async () => {
  const server = http.createServer();
  const base = await listen(server);
  const callback = oidc.callbackCode(server, `${base}/callback`, 'expected-state', 1000);
  const forged = await fetch(`${base}/callback?state=forged&code=ATTACKER_CODE`);
  assert.equal(forged.status, 400);
  const accepted = await fetch(`${base}/callback?state=expected-state&code=ONE_TIME_CODE`);
  assert.equal(accepted.status, 200);
  assert.equal(await callback.promise, 'ONE_TIME_CODE');
});

test('Java CLI rejects tampered or unknown-key OIDC token signatures', async () => {
  const pair = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const token = signedToken({ iss: 'https://issuer.example', aud: 'client', exp: Math.floor(Date.now() / 1000) + 60 }, pair.privateKey);
  const parsed = oidc.jwtClaims(token);
  assert.equal(oidc.verifyJwtSignature(parsed, { keys: [{ ...pair.publicKey.export({ format: 'jwk' }), kid: 'test-key', alg: 'RS256' }] }).aud, 'client');
  const altered = `${token.slice(0, -2)}xx`;
  assert.throws(() => oidc.verifyJwtSignature(oidc.jwtClaims(altered), { keys: [
    { ...pair.publicKey.export({ format: 'jwk' }), kid: 'test-key', alg: 'RS256' }
  ] }), { code: 'OIDC_INVALID_TOKEN' });
  assert.throws(() => oidc.verifyJwtSignature(parsed, { keys: [] }), { code: 'OIDC_INVALID_TOKEN' });
});
