import {NextRequest} from 'next/server';

export async function forward(request: NextRequest, path: string) {
    if (!['GET', 'HEAD'].includes(request.method)) {
        const origin = request.headers.get('origin');
        let sameOrigin = true;
        if (origin) {
            try {
                sameOrigin = new URL(origin).host === request.headers.get('host')
            } catch {
                sameOrigin = false
            }
        }
        if (!sameOrigin) return Response.json({detail: 'Cross-origin request rejected'}, {status: 403});
    }
    const backend = new URL(process.env.BACKEND_URL || 'http://127.0.0.1:8097');
    const target = new URL(path + request.nextUrl.search, backend);
    const headers = new Headers();
    for (const name of ['cookie', 'content-type', 'accept', 'x-csrf-token', 'x-xsrf-token', 'idempotency-key']) {
        const value = request.headers.get(name);
        if (value) headers.set(name, value);
    }
    try {
        const upstream = await fetch(target, {
            method: request.method,
            headers,
            body: ['GET', 'HEAD'].includes(request.method) ? undefined : await request.arrayBuffer(),
            redirect: 'manual',
            cache: 'no-store',
            signal: AbortSignal.timeout(20000)
        });
        const out = new Headers({'Cache-Control': 'no-store'});
        for (const name of ['content-type', 'content-disposition']) {
            const v = upstream.headers.get(name);
            if (v) out.set(name, v)
        }
        for (const cookie of upstream.headers.getSetCookie()) out.append('set-cookie', cookie);
        const location = upstream.headers.get('location');
        if (location) {
            const url = new URL(location, backend);
            if (path === '/login' && request.method === 'POST' && url.searchParams.has('error')) {
                return Response.json({detail: 'Sign-in failed. Check your username and password.'}, {
                    status: 401,
                    headers: out
                });
            }
            if (path.startsWith('/api/') && (url.pathname === '/login' || url.pathname.startsWith('/oauth2/'))) return Response.json({detail: 'Sign in to continue'}, {
                status: 401,
                headers: out
            });
            out.set('location', url.origin === backend.origin ? (url.pathname === '/' ? '/' : '/auth' + url.pathname) + url.search : url.href);
        }
        return new Response(upstream.body, {status: upstream.status, headers: out});
    } catch {
        return Response.json({detail: 'Cannot reach the marketplace API. Check that the backend is running.'}, {status: 502})
    }
}
