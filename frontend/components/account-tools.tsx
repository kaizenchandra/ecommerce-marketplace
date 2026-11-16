'use client';
import {useEffect, useState} from 'react';
import {api, money} from '../lib/api';
import {Form} from './forms';

type Props = { run: (f: () => Promise<void>) => void, busy: boolean };

export function AccountTools({run, busy}: Props) {
    const [sessions, setSessions] = useState<any[]>([]), [addresses, setAddresses] = useState<any[]>([]), [returned, setReturned] = useState<any>(null), [error, setError] = useState('');

    async function refresh() {
        const [s, a] = await Promise.all([api('/sessions'), api('/customers/me/addresses')]);
        setSessions(s);
        setAddresses(a);
        setError('')
    }

    useEffect(() => {
        refresh().catch(e => setError(e.message))
    }, []);
    return <section className="panel"><h2>Address book & sessions</h2>{error && <p role="alert">{error}</p>}
        <button disabled={busy} onClick={() => run(refresh)}>Refresh account details</button>
        <h3>Saved addresses</h3>{addresses.length ? addresses.map(a => <p
                key={a.id}>{a.line1}, {a.city}, {a.postal_code} · {a.country}</p>) :
            <p>No saved addresses. Add an address in your bag.</p>}<h3>Active sessions</h3>{sessions.map((s, i) => <p
            key={i}>Last active {new Date(s.lastAccessedAt).toLocaleString()} · expires
            after {Math.round(s.expiresAfterSeconds / 60)} minutes of inactivity</p>)}<Form title="Track a return"
                                                                                            fields={['returnId:Return ID']}
                                                                                            busy={busy} run={run}
                                                                                            submit={async d => setReturned(await api('/returns/' + encodeURIComponent(d.returnId)))}/>{returned &&
            <article aria-label="Return status"><h3>Return {returned.id}</h3><p>Status: <b>{returned.status}</b></p>
                <p>{returned.quantity} units · {returned.reason}</p><p>Refund
                    allocation: {money(returned.refund_amount)}</p></article>}</section>
}

export function ShippingProfile({sellerId, run, busy, notify}: Props & {
    sellerId: string,
    notify: (s: string) => void
}) {
    return <section className="panel"><h2>Fulfillment settings</h2><p>Set your dispatch origin and maximum parcel
        dimensions. Every offered variant must have a positive weightGrams attribute before enabling a shipping profile;
        unweighted legacy variants cannot be checked out with a profile. The backend has no variant-weight update or
        profile-delete API. The backend replaces these settings when saved; it does not expose a read endpoint.</p><Form
        title="Save shipping profile"
        fields={['name:Sender name', 'street1:Origin street address', 'city:Origin city', 'zip:Origin postal code', 'country:Country code (e.g. IN)', 'lengthCm:Length (cm)', 'widthCm:Width (cm)', 'heightCm:Height (cm)', 'maxWeightGrams:Maximum weight (grams)']}
        busy={busy} run={run} submit={async d => {
        const dimensions = Object.fromEntries(['lengthCm', 'widthCm', 'heightCm', 'maxWeightGrams'].map(k => [k, Number(d[k])]));
        for (const [k, v] of Object.entries(dimensions)) if (!Number.isInteger(v) || v < 1 || v > (k === 'maxWeightGrams' ? 1000000 : 500)) throw Error('Use whole dimensions from 1 to 500 cm and weight from 1 to 1,000,000 grams.');
        if (!/^[A-Z]{2}$/.test(d.country)) throw Error('Use a two-letter uppercase country code.');
        await api('/sellers/' + sellerId + '/shipping-profile', 'PUT', {
            address: {
                name: d.name,
                street1: d.street1,
                city: d.city,
                zip: d.zip,
                country: d.country
            }, ...dimensions
        });
        notify('Shipping profile saved');
    }}/></section>
}
