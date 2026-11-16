// Local simulator documents are authenticated API resources; keep them same-origin.
export function safeUrl(value: string) {
    if (/^\/api\/v1\/shipping-labels\/[0-9a-f-]+\/document$/i.test(value)) return value;
    try {
        const url = new URL(value);
        return ['https:', 'http:'].includes(url.protocol) ? url.href : '#';
    } catch {
        return '#'
    }
}
