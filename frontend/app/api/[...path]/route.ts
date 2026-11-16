import {NextRequest} from 'next/server';
import {forward} from '../../../lib/proxy';

export const dynamic = 'force-dynamic';

async function handler(r: NextRequest, {params}: { params: Promise<{ path: string[] }> }) {
    const {path} = await params;
    return forward(r, '/api/' + path.map(encodeURIComponent).join('/'))
}

export {handler as GET, handler as POST, handler as PUT, handler as PATCH, handler as DELETE};
