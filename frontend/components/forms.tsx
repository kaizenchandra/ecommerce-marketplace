'use client';
export {safeUrl} from '../lib/links';

export function Form({title, fields, submit, run, busy}: {
    title: string,
    fields: string[],
    submit: (d: Record<string, string>) => Promise<void>,
    run: (f: () => Promise<void>) => void,
    busy: boolean
}) {
    return <form className="data-form" onSubmit={e => {
        e.preventDefault();
        const form = e.currentTarget;
        const data = Object.fromEntries(new FormData(form)) as Record<string, string>;
        run(async () => {
            await submit(data);
            form.reset()
        })
    }}><h3>{title}</h3>{fields.map(f => {
        const [name, label] = f.split(':');
        return <label key={name}>{label}<input name={name} required maxLength={name === 'body' ? 2000 : 240}/></label>
    })}
        <button disabled={busy}>{busy ? 'Working…' : title}</button>
    </form>
}
