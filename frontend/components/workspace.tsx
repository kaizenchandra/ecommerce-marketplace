'use client';
import {useEffect, useRef, useState} from 'react';
import {api, money} from '../lib/api';
import {Form, safeUrl} from './forms';
import {ShippingProfile} from './account-tools';
import {Recovery} from './recovery';
import {VariantForm} from './variant-form';

type Row = Record<string, any>;

export function Workspace({view, run, busy, notify}: {
    view: string,
    run: (f: () => Promise<void>) => void,
    busy: boolean,
    notify: (s: string) => void
}) {
    const [data, setData] = useState<Record<string, any>>({}), [error, setError] = useState(''), [loading, setLoading] = useState(true);

    async function load() {
        setLoading(true);
        try {
            if (view === 'seller') {
                const profile = await api('/seller/me').catch(e => {
                    if (e.status === 400 || e.status === 404) return null;
                    throw e
                });
                const [offers, orders, labels] = profile ? await Promise.all([api('/seller/offers'), api('/seller/orders'), api('/seller/labels')]) : [[], [], []];
                setData({profile, offers, orders, labels})
            } else {
                const paths = ['sellers', 'shipments', 'returns', 'status', 'tax-reporting', 'shipping-labels'];
                const values = await Promise.all(paths.map(p => api('/admin/' + p)));
                setData(Object.fromEntries(paths.map((p, i) => [p, values[i]])))
            }
            setError('');
        } catch (e) {
            setError((e as Error).message)
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        load()
    }, [view]);
    const save = async (path: string, method: string, body: unknown) => {
        await api(path, method, body);
        notify('Changes saved');
        await load()
    };
    return <>
        <div className="section-heading">
            <div><span className="eyebrow">YOUR WORKSPACE</span>
                <h1>{view === 'seller' ? 'Seller desk' : 'Marketplace operations'}</h1></div>
            <button disabled={busy || loading} onClick={() => run(load)}>Refresh</button>
        </div>
        {error && <p role="alert" className="alert">{error}</p>}{loading && <p role="status">Loading workspace…</p>}
        {view === 'seller' && <><p>{data.profile?.name} <span className="badge">{data.profile?.status}</span>
        </p>{!data.profile && !loading &&
            <Form title="Request seller onboarding" fields={['name:Store name']} busy={busy} run={run}
                  submit={d => save('/sellers', 'POST', d)}/>}
            {data.profile && <>
                <div className="stats">
                    <article><small>YOUR OFFERS</small><h2>{data.offers.length}</h2></article>
                    <article><small>ON-HAND UNITS</small>
                        <h2>{data.offers.reduce((n: number, o: Row) => n + o.on_hand, 0)}</h2></article>
                    <article><small>RESERVED UNITS</small>
                        <h2>{data.offers.reduce((n: number, o: Row) => n + o.reserved, 0)}</h2></article>
                </div>
                <Form title="Create offer" fields={['variantId:Variant UUID', 'price:Price (INR)']} busy={busy}
                      run={run}
                      submit={d => save('/sellers/' + data.profile.id + '/offers', 'POST', d)}/><ShippingProfile
                sellerId={data.profile.id} run={run} busy={busy} notify={notify}/><h2>Offers & inventory</h2><p
                role="status">Low-stock offers (5 or fewer available
                units): {data.offers.filter((o: Row) => o.on_hand - o.reserved <= 5).map((o: Row) => o.sku).join(", ") || "None"}</p>
                <div className="workspace-grid">{data.offers.map((o: Row) => <article className="panel" key={o.id}>
                    <h3>{o.sku}</h3><p>{money(o.price)} · {o.on_hand} on hand · {o.reserved} reserved</p><Form
                    title="Save price" fields={['price:New price (INR)']} busy={busy} run={run}
                    submit={d => save('/sellers/' + data.profile.id + '/offers/' + o.id, 'PATCH', {variantId: o.variant_id, ...d})}/><Form
                    title="Adjust stock" fields={['delta:Units to add or subtract', 'reason:Adjustment reason']}
                    busy={busy} run={run}
                    submit={d => save('/sellers/' + data.profile.id + '/inventory/adjustments', 'POST', {
                        ...d,
                        offerId: o.id,
                        delta: Number(d.delta)
                    })}/></article>)}</div>
                <h2>Fulfillment</h2>{data.orders.map((g: Row) => <Fulfillment key={g.item_id || g.id} g={g} run={run}
                                                                              busy={busy} done={load}/>)}<h2>Shipping
                labels</h2>{data.labels.map((l: Row) => <article className="line" key={l.id}>
                <div><h3>Label {l.id}</h3><span className="badge">{l.status}</span><p>{l.quantity} units ·
                    ceiling {money(l.max_cost)}</p>{l.label_url &&
                    <a href={safeUrl(l.label_url)} target="_blank" rel="noopener noreferrer">Download label ↗</a>}</div>
                <div>{l.status === 'PURCHASED' && <button disabled={busy}
                                                          onClick={() => run(() => save('/shipping-labels/' + l.id + '/dispatch', 'POST', {}))}>Confirm
                    physical dispatch</button>}{['PURCHASED', 'DISPATCHED'].includes(l.status) &&
                    <button disabled={busy}
                            onClick={() => run(() => save('/shipping-labels/' + l.id + '/refresh', 'POST', {}))}>Refresh
                        download</button>}{!l.purchase_started_at && l.status !== 'ABORTED' && <button disabled={busy}
                                                                                                       onClick={() => run(() => save('/shipping-labels/' + l.id + '/abort', 'POST', {}))}>Abort
                    purchase</button>}</div>
            </article>)}</>}
        </>}
        {view === 'admin' && data.sellers && <><h2>Seller approvals</h2>{data.sellers.map((s: Row) => <article
            className="line" key={s.id}>
            <div><h3>{s.name}</h3><span className="badge">{s.status}</span></div>
            <div>{['ACTIVE', 'SUSPENDED', 'REJECTED', 'CLOSED'].map(status => <button key={status}
                                                                                      disabled={busy || s.status === status}
                                                                                      onClick={() => run(() => save('/admin/sellers/' + s.id, 'PATCH', {status}))}>{status === 'ACTIVE' ? 'Approve' : status.toLowerCase()}</button>)}</div>
        </article>)}
            <div className="workspace-grid"><Form title="Create category"
                                                  fields={['id:Category slug', 'name:Category name']} busy={busy}
                                                  run={run} submit={d => save('/admin/categories', 'POST', d)}/><Form
                title="Create product"
                fields={['name:Product name', 'brand:Brand', 'description:Description', 'categoryId:Category ID']}
                busy={busy} run={run} submit={async d => {
                const p = await api('/admin/products', 'POST', d);
                notify('Created product: ' + p.id)
            }}/><VariantForm run={run} busy={busy} notify={notify}/><Form title="Moderate product"
                                                                          fields={['id:Product UUID', 'status:Status (ACTIVE or HIDDEN)']}
                                                                          busy={busy} run={run}
                                                                          submit={d => save('/admin/products/' + d.id, 'PATCH', {status: d.status})}/><Form
                title="Hide review" fields={['id:Review UUID']} busy={busy} run={run}
                submit={d => save('/admin/reviews/' + d.id, 'PATCH', {})}/><Form title="Deactivate customer"
                                                                                 fields={['subject:Customer subject']}
                                                                                 busy={busy} run={run}
                                                                                 submit={d => save('/admin/customers/' + encodeURIComponent(d.subject) + '/deactivate', 'POST', {})}/>
            </div>
            <h2>Sandbox delivery</h2>{data.shipments.map((s: Row) => <article className="line" key={s.id}>
                <div><h3>{s.tracking}</h3><span className="badge">{s.status}</span></div>
                <button disabled={busy || s.status === 'DELIVERED'}
                        onClick={() => run(() => save('/admin/shipments/' + s.id + '/deliver', 'POST', {}))}>Simulate
                    delivery
                </button>
            </article>)}<h2>Return inspection</h2>{data.returns.map((r: Row) => <article className="line" key={r.id}>
                <div><h3>{r.reason}</h3><p>{r.id} · {r.status}</p></div>
                <button disabled={busy} onClick={() => run(() => save('/admin/returns/' + r.id + '/inspect', 'POST', {
                    accepted: true,
                    restock: true
                }))}>Accept & restock
                </button>
                <button disabled={busy} onClick={() => run(() => save('/admin/returns/' + r.id + '/inspect', 'POST', {
                    accepted: false,
                    restock: false
                }))}>Reject
                </button>
            </article>)}
            <Recovery run={run} busy={busy} notify={notify}/>{['status', 'tax-reporting', 'shipping-labels'].map(k =>
                <details className="panel" key={k}>
                    <summary>{k.replaceAll('-', ' ')} · operational details</summary>
                    <pre>{JSON.stringify(data[k], null, 2)}</pre>
                </details>)}<Form title="Retry refund" fields={['id:Refund UUID']} busy={busy} run={run}
                                  submit={d => save('/admin/refunds/' + d.id + '/retry', 'POST', {})}/></>}
    </>;
}

function Fulfillment({g, run, busy, done}: any) {
    const shipKey = useRef(''), labelKey = useRef('');
    const remaining = g.quantity - g.shipped_quantity - (g.label_reserved_quantity || 0);
    return <article className="panel"><h3>{g.title} × {g.quantity}</h3><p>{g.status} · {g.shipped_quantity} shipped
        · {g.delivered_quantity} delivered · {g.label_reserved_quantity || 0} awaiting label dispatch</p>
        <form key={"ship-" + remaining} onSubmit={e => {
            e.preventDefault();
            const d = Object.fromEntries(new FormData(e.currentTarget));
            if (!shipKey.current) shipKey.current = crypto.randomUUID();
            run(async () => {
                await api('/seller-orders/' + g.id + '/shipments', 'POST', {
                    itemId: g.item_id,
                    quantity: Number(d.quantity),
                    carrier: d.carrier,
                    tracking: d.tracking
                }, shipKey.current);
                shipKey.current = '';
                await done()
            })
        }}><label>Quantity<input name="quantity" type="number" min="1" max={remaining}
                                 defaultValue={Math.max(1, remaining)} required/></label><label>Carrier<input
            name="carrier" required/></label><label>Tracking number<input name="tracking" required/></label>
            <button disabled={busy || remaining <= 0 || !['CONFIRMED', 'PARTIALLY_FULFILLED'].includes(g.status)}>Record
                shipment
            </button>
        </form>
        <form key={"label-" + remaining} onSubmit={e => {
            e.preventDefault();
            const d = Object.fromEntries(new FormData(e.currentTarget));
            if (!labelKey.current) labelKey.current = crypto.randomUUID();
            run(async () => {
                if (!/^\d+(\.\d{1,2})?$/.test(String(d.ceiling))) throw Error('Enter a price with up to two decimal places');
                await api('/seller-orders/' + g.id + '/labels', 'POST', {
                    itemId: g.item_id,
                    quantity: Number(d.quantity),
                    maxCostMinor: Math.round(Number(d.ceiling) * 100)
                }, labelKey.current);
                labelKey.current = '';
                await done()
            })
        }}><label>Label quantity<input name="quantity" type="number" min="1" max={remaining} defaultValue="1" required/></label><label>Maximum
            label cost (INR)<input name="ceiling" type="number" min="0" step="0.01" defaultValue="100"
                                   required/></label>
            <button disabled={busy || remaining <= 0 || !['CONFIRMED', 'PARTIALLY_FULFILLED'].includes(g.status)}>Buy
                test shipping label
            </button>
        </form>
    </article>
}
