import {after, before, test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
import {once} from 'node:events';

let backend, app;
const origin = 'http://127.0.0.1:3198';
before(async () => {
    backend = createServer(async (req, res) => {
        if (req.url === '/api/v1/private') {
            res.writeHead(302, {location: '/login'});
            return res.end()
        }
        if (req.url === '/login' && req.method === 'POST') {
            res.writeHead(302, {location: '/login?error', 'set-cookie': 'SESSION=failed-login; Path=/; HttpOnly'});
            return res.end()
        }
        if (req.url === '/login') {
            res.writeHead(302, {location: '/'});
            return res.end()
        }
        if (req.url === '/api/v1/csrf') {
            res.writeHead(200, {
                'content-type': 'application/json',
                'set-cookie': ['SESSION=test-session; Path=/; HttpOnly; SameSite=Lax']
            });
            return res.end(JSON.stringify({token: 'token', headerName: 'X-CSRF-TOKEN'}))
        }
        let body = '';
        for await(const chunk of req) body += chunk;
        res.writeHead(200, {'content-type': 'application/json'});
        res.end(JSON.stringify({
            url: req.url,
            method: req.method,
            cookie: req.headers.cookie,
            csrf: req.headers['x-csrf-token'],
            key: req.headers['idempotency-key'],
            body
        }));
    });
    backend.listen(3199, '127.0.0.1');
    await once(backend, 'listening');
    app = spawn(process.execPath, ['node_modules/next/dist/bin/next', 'start', '--hostname', '127.0.0.1', '--port', '3198'], {
        cwd: process.cwd(),
        env: {...process.env, BACKEND_URL: 'http://127.0.0.1:3199'},
        stdio: 'pipe'
    });
    for (let i = 0; i < 100; i++) {
        try {
            if ((await fetch(origin)).ok) return
        } catch {
        }
        await new Promise(r => setTimeout(r, 100))
    }
    throw Error('Next server did not start');
});
after(async () => {
    app?.kill();
    if (backend) await new Promise(r => backend.close(r))
});
test('serves the real frontend', async () => {
    const r = await fetch(origin);
    assert.equal(r.status, 200);
    assert.match(await r.text(), /Good finds/);
});
test('forwards API queries and session cookies without caching', async () => {
    const r = await fetch(origin + '/api/v1/products?q=book', {headers: {cookie: 'SESSION=abc'}});
    assert.equal(r.headers.get('cache-control'), 'no-store');
    const d = await r.json();
    assert.equal(d.url, '/api/v1/products?q=book');
    assert.equal(d.cookie, 'SESSION=abc')
});
test('preserves CSRF cookie and token', async () => {
    const r = await fetch(origin + '/api/v1/csrf');
    assert.match(r.headers.get('set-cookie'), /SESSION=test-session/);
    assert.equal((await r.json()).token, 'token')
});
test('forwards mutation payload, CSRF and idempotency key', async () => {
    const r = await fetch(origin + '/api/v1/checkouts', {
        method: 'POST',
        headers: {origin, 'content-type': 'application/json', 'x-csrf-token': 'token', 'idempotency-key': 'retry-safe'},
        body: '{"quoteId":"123"}'
    });
    const d = await r.json();
    assert.equal(d.method, 'POST');
    assert.equal(d.csrf, 'token');
    assert.equal(d.key, 'retry-safe');
    assert.equal(d.body, '{"quoteId":"123"}')
});
test('blocks cross-origin writes', async () => {
    const r = await fetch(origin + '/api/v1/cart', {method: 'DELETE', headers: {origin: 'https://untrusted.example'}});
    assert.equal(r.status, 403)
});
test('converts backend login redirects to API 401', async () => {
    const r = await fetch(origin + '/api/v1/private');
    assert.equal(r.status, 401);
    assert.equal((await r.json()).detail, 'Sign in to continue')
});
test('rewrites local login success to frontend root', async () => {
    const r = await fetch(origin + '/auth/login', {redirect: 'manual'});
    assert.equal(r.status, 302);
    assert.equal(r.headers.get('location'), '/')
});
test('does not proxy arbitrary authentication paths', async () => {
    const r = await fetch(origin + '/auth/admin/status');
    assert.equal(r.status, 404)
});

test('returns explicit failed-login error without following error-page redirects', async () => {
    const r = await fetch(origin + '/auth/login', {
        method: 'POST',
        headers: {origin, 'content-type': 'application/x-www-form-urlencoded'},
        body: 'username=test&password=bad'
    });
    assert.equal(r.status, 401);
    assert.equal(r.redirected, false);
    assert.match((await r.json()).detail, /Sign-in failed/);
    assert.match(r.headers.get('set-cookie'), /SESSION=failed-login/)
});

test('forwards PUT shipping profiles with session and CSRF protection', async () => {
    const r = await fetch(origin + '/api/v1/sellers/test/shipping-profile', {
        method: 'PUT',
        headers: {origin, 'content-type': 'application/json', 'x-csrf-token': 'token', cookie: 'SESSION=seller'},
        body: '{"lengthCm":20}'
    });
    assert.equal(r.status, 200);
    const d = await r.json();
    assert.equal(d.method, 'PUT');
    assert.equal(d.csrf, 'token');
    assert.equal(d.cookie, 'SESSION=seller');
    assert.equal(d.body, '{"lengthCm":20}')
});
