import {NextRequest} from 'next/server';
import {forward} from '../../../lib/proxy';

async function handler(r: NextRequest, {params}: { params: Promise<{ path: string[] }> }) {
    const {path} = await params;
    if (!['login', 'logout', 'oauth2'].includes(path[0])) return new Response(null, {status: 404});
    return forward(r, '/' + path.map(encodeURIComponent).join('/'));
}

export {handler as GET, handler as POST};
