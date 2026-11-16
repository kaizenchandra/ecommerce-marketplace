'use client';
import {useEffect, useState} from 'react';
import {api} from '../lib/api';
import {Form} from './forms';

export function Recovery({run, busy, notify}: {
    run: (f: () => Promise<void>) => void,
    busy: boolean,
    notify: (s: string) => void
}) {
    const [events, setEvents] = useState<any[]>([]), [failures, setFailures] = useState<any[]>([]), [error, setError] = useState('');

    async function load() {
        const [e, f] = await Promise.all([api('/admin/payment-events'), api('/admin/events/failures')]);
        setEvents(e);
        setFailures(f);
        setError('')
    }

    useEffect(() => {
        load().catch(e => setError(e.message))
    }, []);

    async function post(path: string) {
        await api('/admin/' + path, 'POST', {});
        notify('Recovery request accepted. Refresh operational status to inspect the result.');
        await load()
    }

    const enc = encodeURIComponent;
    const actions = [
        ['Retry checkout workflow', ['id:Order ID'], (d: any) => 'workflows/' + enc(d.id) + '/retry'],
        ['Retry outbox delivery', ['id:Outbox event ID'], (d: any) => 'events/outbox/' + enc(d.id) + '/retry'],
        ['Retry shipment tracking', ['id:Shipment ID'], (d: any) => 'shipments/' + enc(d.id) + '/retry-tracking'],
        ['Retry tax report', ['id:Tax reporting job ID'], (d: any) => 'tax-reporting/' + enc(d.id) + '/retry'],
        ['Reconcile tax report', ['id:Tax reporting job ID', 'providerId:Known provider transaction ID'], (d: any) => 'tax-reporting/' + enc(d.id) + '/reconcile/' + enc(d.providerId)],
        ['Retry shipping purchase', ['id:Shipping label ID'], (d: any) => 'shipping-labels/' + enc(d.id) + '/retry'],
        ['Reconcile shipping purchase', ['id:Shipping label ID', 'providerId:Known provider purchase ID'], (d: any) => 'shipping-labels/' + enc(d.id) + '/reconcile/' + enc(d.providerId)],
        ['Abort unattempted shipping purchase', ['id:Shipping label ID'], (d: any) => 'shipping-labels/' + enc(d.id) + '/abort'],
        ['Release closed shipping purchase', ['id:Shipping label ID'], (d: any) => 'shipping-labels/' + enc(d.id) + '/release-closed']
    ] as const;
    return <section><h2>Recovery desk</h2><p>Inspect failures before retrying. Reconciliation requires a verified
        provider reference; the backend checks state and provider evidence before changing fulfillment or financial
        records.</p>{error && <p role="alert">{error}</p>}
        <button disabled={busy} onClick={() => run(load)}>Refresh recovery queues</button>
        <h3>Payment webhook events</h3>{!events.length && <p>No payment webhook events.</p>}{events.map(e => <article
            className="line" key={e.provider + e.event_id}>
            <div><h3>{e.provider} · {e.event_id}</h3><p>{e.status} · {e.attempts} attempts</p><p>{e.last_error}</p>
            </div>
            <button disabled={busy || e.status !== 'MANUAL_REVIEW'}
                    onClick={() => run(() => post('payment-events/' + enc(e.provider) + '/' + enc(e.event_id) + '/retry'))}>Retry
                payment event
            </button>
        </article>)}<h3>Event delivery failures</h3>{!failures.length &&
            <p>No event delivery failures.</p>}{failures.map(f => <article className="line" key={f.id}>
            <div><h3>{f.id}</h3><p>{f.last_error || f.error || f.reason}</p>
                <details>
                    <summary>Failure payload</summary>
                    <pre>{JSON.stringify(f.payload, null, 2)}</pre>
                </details>
            </div>
            <button disabled={busy} onClick={() => run(() => post('events/failures/' + enc(f.id) + '/replay'))}>Replay
                failed event
            </button>
        </article>)}
        <div className="workspace-grid">{actions.map(([title, fields, path]) => <Form key={title} title={title}
                                                                                      fields={[...fields]} busy={busy}
                                                                                      run={run}
                                                                                      submit={d => post(path(d))}/>)}</div>
    </section>
}
