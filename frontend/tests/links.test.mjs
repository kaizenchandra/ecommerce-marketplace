import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import ts from 'typescript';

const source = readFileSync(new URL('../lib/links.ts', import.meta.url), 'utf8');
const js = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.ESNext}}).outputText;
const {safeUrl} = await import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'));
test('simulator label document keeps its authenticated same-origin path', () => {
    assert.equal(safeUrl('/api/v1/shipping-labels/85ce4c17-a19a-4d28-938d-944ee11bc24a/document'), '/api/v1/shipping-labels/85ce4c17-a19a-4d28-938d-944ee11bc24a/document');
});
test('allows hosted HTTPS provider links', () => assert.equal(safeUrl('https://checkout.stripe.com/c/test'), 'https://checkout.stripe.com/c/test'));
test('rejects executable, protocol-relative and arbitrary relative links', () => {
    for (const v of ['javascript:alert(1)', 'data:text/html,bad', '//untrusted.example', '/login', '/api/v1/shipping-labels/../document', '/api/v1/shipping-labels/123/document?next=//evil']) assert.equal(safeUrl(v), '#');
});
