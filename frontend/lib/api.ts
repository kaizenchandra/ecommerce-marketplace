export class ApiError extends Error {
    constructor(message: string, public status: number) {
        super(message)
    }
}

export async function api(path: string, method = 'GET', data?: unknown, key?: string) {
    const headers: Record<string, string> = {'Accept': 'application/json'};
    if (method !== 'GET') {
        const r = await fetch('/api/v1/csrf', {cache: 'no-store'});
        if (!r.ok) throw new Error('Could not establish a secure session');
        const csrf = await r.json();
        headers[csrf.headerName] = csrf.token;
        headers['Content-Type'] = 'application/json';
    }
    if (key) headers['Idempotency-Key'] = key;
    const r = await fetch('/api/v1' + path, {
        method,
        headers,
        body: data === undefined ? undefined : JSON.stringify(data),
        cache: 'no-store'
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok) throw new ApiError(body.detail || (r.status === 403 ? 'Your account does not have access to this action.' : 'Request failed. Please try again.'), r.status);
    return body;
}

export const money = (n: number) => new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR'
}).format(n / 100);
