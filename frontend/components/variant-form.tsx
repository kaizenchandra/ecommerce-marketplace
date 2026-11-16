'use client';
import {api} from '../lib/api';

export function VariantForm({run, busy, notify}: {
    run: (f: () => Promise<void>) => void,
    busy: boolean,
    notify: (s: string) => void
}) {
    return <form className="data-form" onSubmit={e => {
        e.preventDefault();
        const form = e.currentTarget;
        const d = Object.fromEntries(new FormData(form));
        run(async () => {
            let attributes: Record<string, string> = {};
            try {
                attributes = JSON.parse(String(d.attributes || '{}'))
            } catch {
                throw Error('Custom attributes must be valid JSON.')
            }
            if (!attributes || Array.isArray(attributes) || typeof attributes !== 'object' || Object.values(attributes).some(v => typeof v !== 'string')) throw Error('Use a JSON object with string values for attributes.');
            const grams = Number(d.weightGrams);
            if (!Number.isInteger(grams) || grams < 1 || grams > 1000000) throw Error('Weight must be a whole number from 1 to 1,000,000 grams.');
            const v = await api('/admin/products/' + d.productId + '/variants', 'POST', {
                sku: d.sku,
                attributes: {...attributes, ...(d.color ? {color: String(d.color)} : {}), weightGrams: String(grams)}
            });
            notify('Created variant: ' + v.id);
            form.reset();
        })
    }}><h3>Create variant</h3><label>Product UUID<input name="productId" required/></label><label>SKU<input name="sku"
                                                                                                            required
                                                                                                            maxLength={100}/></label><label>Color<input
        name="color"/></label><label>Weight (grams)<input name="weightGrams" type="number" min={1} max={1000000}
                                                          required/></label><label>Additional attributes (JSON)<textarea
        aria-label="Additional attributes (JSON)" name="attributes" defaultValue="{}" rows={3}
        maxLength={3000}/></label>
        <button disabled={busy}>{busy ? 'Working…' : 'Create variant'}</button>
    </form>
}
