'use client';
import {useCallback, useEffect, useRef, useState} from 'react';
import {api, ApiError, money} from '../lib/api';
import {Workspace} from '../components/workspace';
import {AccountTools} from '../components/account-tools';
import {Form, safeUrl} from '../components/forms';

type Row = Record<string, any>;
const empty = 'Nothing here yet.';
export default function Marketplace() {
    const [view, setView] = useState('shop'), [user, setUser] = useState<Row | null>(null), [rows, setRows] = useState<Row[]>([]), [categories, setCategories] = useState<Row[]>([]), [facets, setFacets] = useState<Row[]>([]), [cart, setCart] = useState<Row[]>([]), [addresses, setAddresses] = useState<Row[]>([]);
    const [filters, setFilters] = useState({
        q: '',
        category: '',
        brand: '',
        min: '',
        max: '',
        sort: 'relevance',
        page: 0
    }), [detail, setDetail] = useState<Row | null>(null), [quote, setQuote] = useState<Row | null>(null), [order, setOrder] = useState<Row | null>(null), [notice, setNotice] = useState(''), [error, setError] = useState(''), [loading, setLoading] = useState(false), [busy, setBusy] = useState(false), [now, setNow] = useState(Date.now()), [suggestions, setSuggestions] = useState<Row[]>([]);
    const [orderPage, setOrderPage] = useState(0), [reviewPage, setReviewPage] = useState(0);
    const pending = useRef(false);
    const key = useRef(''), generation = useRef(0), dialog = useRef<HTMLDialogElement>(null);
    const roles = (user?.roles || []).map((r: Row) => r.authority);
    const requireLogin = () => {
        generation.current++;
        setRows([]);
        dialog.current?.close();
        setDetail(null);
        setQuote(null);
        setOrder(null);
        setUser(null);
        setCart([]);
        setAddresses([]);
        setView('login')
    };
    const run = async (fn: () => Promise<void>) => {
        if (pending.current) return;
        pending.current = true;
        setBusy(true);
        setError('');
        try {
            await fn()
        } catch (e) {
            setError((e as Error).message);
            if (e instanceof ApiError && e.status === 401) requireLogin()
        } finally {
            pending.current = false;
            setBusy(false)
        }
    };
    const loadCart = async () => {
        const [c, a] = await Promise.all([api('/cart'), api('/customers/me/addresses')]);
        setCart(c);
        setAddresses(a)
    };
    useEffect(() => {
        api('/customers/me').then(setUser).catch(() => {
        });
        api('/categories').then(setCategories).catch(() => {
        });
        api('/search/facets').then(setFacets).catch(() => {
        })
    }, []);
    useEffect(() => {
        if (!user) return;
        api('/cart').then(setCart).catch(() => {
        })
    }, [user]);
    useEffect(() => {
        const timer = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(timer)
    }, []);
    useEffect(() => {
        if (filters.q.length < 2) {
            setSuggestions([]);
            return
        }
        let active = true;
        const t = setTimeout(() => api('/search/suggestions?q=' + encodeURIComponent(filters.q)).then(v => {
            if (active) setSuggestions(v)
        }).catch(() => {
        }), 300);
        return () => {
            active = false;
            clearTimeout(t)
        }
    }, [filters.q]);
    const load = useCallback(async () => {
        const seq = ++generation.current;
        setLoading(true);
        setError('');
        setRows([]);
        try {
            let data;
            if (view === 'shop') {
                const p = new URLSearchParams();
                Object.entries(filters).forEach(([k, v]) => {
                    if (v !== '') p.set(k, ['min', 'max'].includes(k) ? String(Math.round(Number(v) * 100)) : String(v))
                });
                data = await api('/products?' + p)
            } else if (view === 'cart') {
                await loadCart();
                return
            } else if (view === 'orders') data = await api('/orders?page=' + orderPage);
            else if (view === 'account') data = await api('/notifications');
            if (seq === generation.current && data) setRows(data);
        } catch (e) {
            if (seq === generation.current) {
                setError((e as Error).message);
                if (e instanceof ApiError && e.status === 401) requireLogin()
            }
        } finally {
            if (seq === generation.current) setLoading(false)
        }
    }, [view, filters, orderPage]);
    useEffect(() => {
        load()
    }, [load]);
    useEffect(() => {
        if (detail) {
            dialog.current?.showModal()
        }
    }, [detail]);

    function change(k: string, v: string) {
        setFilters(f => ({...f, [k]: v, page: 0}))
    }

    async function openProduct(id: string) {
        setReviewPage(0);
        const [d, r, recs] = await Promise.all([api('/products/' + id), api('/products/' + id + '/reviews'), api('/products/' + id + '/recommendations')]);
        setDetail({...d, ...r, recs})
    }

    async function openOrder(id: string) {
        const [o, s, refunds] = await Promise.all([api('/orders/' + id), api('/orders/' + id + '/shipments'), api('/orders/' + id + '/refunds')]);
        setOrder({...o, shipments: s, refunds})
    }

    async function reviewsPage(page: number) {
        const r = await api('/products/' + detail!.product.id + '/reviews?page=' + page);
        setReviewPage(page);
        setDetail((d: Row | null) => d ? {...d, ...r} : d)
    }

    const expired = quote && new Date(quote.expires_at).getTime() <= now;
    const nav = (v: string) => {
        generation.current++;
        setRows([]);
        setView(v);
        setError('');
        setOrder(null)
    };
    return <><a className="skip" href="#main">Skip to content</a>
        <div className="announcement">Independent stores. Thoughtful
            finds. <span>One marketplace, more possibilities.</span><b>INR · India</b></div>
        <header>
            <button className="logo" onClick={() => nav('shop')}>market<span>lane</span><i>↗</i></button>
            <label className="search"><span>⌕</span><input aria-label="Search products"
                                                           placeholder="Find your next good thing…" value={filters.q}
                                                           onChange={e => {
                                                               nav('shop');
                                                               change('q', e.target.value)
                                                           }} list="suggestions"/><kbd>Search</kbd>
                <datalist id="suggestions">{suggestions.map(s => <option key={s.name} value={s.name}/>)}</datalist>
            </label>
            <button className="plain account-button"
                    aria-label={user ? "Account: " + (user.profile?.display_name || "Your account") : "Sign in"}
                    onClick={() => nav(user ? 'account' : 'login')}>♙ <span>{user?.profile?.display_name || 'Sign in'}</span>
            </button>
            <button className="cart-button"
                    onClick={() => nav('cart')}>Bag <b>{cart.reduce((n, c) => n + c.quantity, 0)}</b></button>
        </header>
        <nav
            aria-label="Main navigation">{[['shop', 'Discover'], ['orders', 'Your orders'], ...(roles.includes('ROLE_SELLER') ? [['seller', 'Seller desk']] : []), ...(roles.includes('ROLE_ADMIN') ? [['admin', 'Operations']] : [])].map(([v, label]) =>
            <button key={v} className={view === v ? 'active' : ''} onClick={() => nav(v)}>{label}</button>)}<span>Small stores. Big possibilities.</span>
        </nav>
        <main id="main">{error && <div className="alert" role="alert">{error}
            <button className="plain" onClick={() => setError('')}>Dismiss</button>
        </div>}{notice && <div className="toast" role="status">{notice}
            <button className="plain" onClick={() => setNotice('')}>×</button>
        </div>}
            {view === 'shop' && <>
                <section className="hero">
                    <div><span className="eyebrow">THE EVERYDAY, ELEVATED</span><h1>Good finds.<br/>Great little
                        discoveries.</h1><p>Explore goods from independent sellers.<br/>Find something that feels like
                        you.</p><a className="button dark" href="#catalog">Explore the collection <span>↗</span></a>
                        <div className="hero-foot">COMPARE OFFERS <i/> SHOP YOUR WAY <i/> SUPPORT INDEPENDENT</div>
                    </div>
                    <div className="hero-art" aria-hidden="true">
                        <div className="orbit"/>
                        <div className="headphones">♬</div>
                        <div className="book">THE ART<br/>OF EVERYDAY<span>A little inspiration.</span></div>
                        <div className="art-label">Considered essentials.<br/><b>Endless possibilities.</b></div>
                        <span className="spark">✳</span></div>
                </section>
                <div className="benefits"><span>◇ <b>Independent sellers</b> More choice, one place</span><span>⇄ <b>Compare with confidence</b> Real offers, clear prices</span><span>♧ <b>Shop securely</b> Protected checkout</span>
                </div>
                <section id="catalog">
                    <div className="section-heading">
                        <div><span className="eyebrow">FIND YOUR NEXT FAVORITE</span>
                            <h2>{filters.q ? 'Results for “' + filters.q + '”' : 'Explore the marketplace'}<span
                                className="dot">.</span></h2></div>
                        <select aria-label="Sort products" value={filters.sort}
                                onChange={e => change('sort', e.target.value)}>
                            <option value="relevance">Recommended</option>
                            <option value="price">Price: low to high</option>
                            <option value="price_desc">Price: high to low</option>
                        </select></div>
                    <div className="catalog-layout">
                        <aside><h3>Browse by category</h3>
                            <button className={!filters.category ? 'selected' : ''}
                                    onClick={() => change('category', '')}>All discoveries <span>↗</span></button>
                            {categories.map(c => <button key={c.id}
                                                         className={filters.category === c.id ? 'selected' : ''}
                                                         onClick={() => change('category', c.id)}>{c.name}</button>)}
                            <hr/>
                            <label>Brand<select value={filters.brand} onChange={e => change('brand', e.target.value)}>
                                <option value="">All brands</option>
                                {Array.from(new Set(facets.map(f => f.brand))).map(b => <option key={b}>{b}</option>)}
                            </select></label><label>Price range (₹)
                                <div className="range"><input type="number" min="0" aria-label="Minimum price"
                                                              placeholder="Min" value={filters.min}
                                                              onChange={e => change('min', e.target.value)}/><input
                                    type="number" min="0" aria-label="Maximum price" placeholder="Max"
                                    value={filters.max} onChange={e => change('max', e.target.value)}/></div>
                            </label>
                            <button className="plain" onClick={() => setFilters({
                                q: '',
                                category: '',
                                brand: '',
                                min: '',
                                max: '',
                                sort: 'relevance',
                                page: 0
                            })}>Reset filters
                            </button>
                        </aside>
                        <div>
                            <div className="results-label">
                                <span>{loading ? 'Finding good things…' : rows.length + ' discoveries on this page'}</span><span>Curated by you, sold by independents</span>
                            </div>
                            <div className="products" aria-busy={loading}>{loading ? [1, 2, 3].map(i => <div
                                className="skeleton" key={i}/>) : rows.map((p, i) => <button className="product"
                                                                                             key={p.id}
                                                                                             onClick={() => run(() => openProduct(p.id))}>
                                <div className={'product-art tone' + i % 4}><span
                                    className="product-tag">{p.category_id}</span><span
                                    className="product-symbol">{p.category_id === 'books' ? '▤' : '♬'}</span>{p.image_url && safeUrl(p.image_url) !== "#" &&
                                    <img className="catalog-image" src={safeUrl(p.image_url)} alt={p.name}
                                         loading="lazy" referrerPolicy="no-referrer"/>}<span
                                    className="explore-icon">↗</span></div>
                                <div className="product-copy"><small>{p.brand}</small><h3>{p.name}</h3>
                                    <p>From <strong>{money(p.price)}</strong></p><span className="subtle">Compare seller offers →</span>
                                </div>
                            </button>)}</div>
                            {!loading && !rows.length && <div className="empty"><h3>No discoveries yet</h3>
                                <p>{error ? 'The catalog could not load. Retry when the API is available.' : 'Try another search or reset your filters.'}</p>
                                <button onClick={() => load()}>Try again</button>
                            </div>}
                            <div className="pagination">
                                <button disabled={!filters.page || loading}
                                        onClick={() => setFilters(f => ({...f, page: f.page - 1}))}>← Previous
                                </button>
                                <span>Page {filters.page + 1}</span>
                                <button disabled={rows.length < 24 || loading}
                                        onClick={() => setFilters(f => ({...f, page: f.page + 1}))}>Next →
                                </button>
                            </div>
                        </div>
                    </div>
                </section>
            </>}
            {view === 'login' &&
                <section className="narrow panel"><span className="eyebrow">WELCOME TO MARKET LANE</span><h1>Make
                    yourself at home.</h1><p>Sign in to save your cart, check out and follow your orders.</p>
                    <form onSubmit={e => {
                        e.preventDefault();
                        const f = new FormData(e.currentTarget);
                        run(async () => {
                            const c = await api('/csrf');
                            const body = new URLSearchParams({
                                username: String(f.get('username')),
                                password: String(f.get('password')),
                                [c.parameterName || '_csrf']: c.token
                            });
                            const r = await fetch('/auth/login', {
                                method: 'POST',
                                headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                                body
                            });
                            if (!r.ok || r.url.includes('error')) throw new Error('Sign-in failed. Check your username and password.');
                            const u = await api('/customers/me');
                            setUser(u);
                            nav('shop')
                        })
                    }}><label>Username<input name="username" autoComplete="username"
                                             required/></label><label>Password<input name="password" type="password"
                                                                                     autoComplete="current-password"
                                                                                     required/></label>
                        <button disabled={busy}>Sign in →</button>
                    </form>
                    <p className="subtle">Local accounts: customer, seller or admin. Use the password configured for
                        your backend.</p><a href="/auth/oauth2/authorization/marketplace">Sign in with your identity
                        provider →</a></section>}
            {view === 'cart' && <><h1>Your shopping bag<span className="dot">.</span></h1>
                <div className="checkout-grid">
                    <section><p>Stock and prices are confirmed when you place an order.</p>{cart.map(i => <article
                        className="line" key={i.id}>
                        <div className="mini-art">◇</div>
                        <div><h3>{i.name}</h3><p>{money(i.price)} each</p><label>Quantity<input type="number" min="1"
                                                                                                max="100"
                                                                                                value={i.quantity}
                                                                                                disabled={busy}
                                                                                                onChange={e => {
                                                                                                    const q = Number(e.target.value);
                                                                                                    if (q < 1 || q > 100) return;
                                                                                                    run(async () => {
                                                                                                        await api('/cart/items/' + i.id, 'PATCH', {quantity: q});
                                                                                                        setQuote(null);
                                                                                                        await loadCart()
                                                                                                    })
                                                                                                }}/></label></div>
                        <strong>{money(i.price * i.quantity)}</strong>
                        <button className="plain" disabled={busy} onClick={() => run(async () => {
                            await api('/cart/items/' + i.id, 'DELETE');
                            setQuote(null);
                            await loadCart()
                        })}>Remove
                        </button>
                    </article>)}{!cart.length &&
                        <div className="empty"><h2>Your bag is waiting</h2><p>Find something you love from an
                            independent store.</p>
                            <button onClick={() => nav('shop')}>Explore the marketplace</button>
                        </div>}{cart.length > 0 && <button className="plain" onClick={() => run(async () => {
                        await api('/cart', 'DELETE');
                        setQuote(null);
                        await loadCart()
                    })}>Clear bag</button>}
                        <h2>Delivery address</h2><Form title="Save a new address"
                                                       fields={['line1:Street address', 'city:City', 'postalCode:Postal code', 'state:State code (e.g. MH)']}
                                                       submit={async d => {
                                                           if (!/^[0-9]{6}$/.test(d.postalCode)) throw Error('Enter a six-digit Indian postal code');
                                                           await api('/customers/me/addresses', 'POST', d);
                                                           await loadCart();
                                                           setNotice('Address saved')
                                                       }} run={run} busy={busy}/></section>
                    <section className="panel summary"><h2>Checkout</h2>
                        <form onChange={() => setQuote(null)} onSubmit={e => {
                            e.preventDefault();
                            const d = Object.fromEntries(new FormData(e.currentTarget));
                            run(async () => {
                                const q = await api('/checkout/quotes', 'POST', d);
                                key.current = crypto.randomUUID();
                                setQuote(q)
                            })
                        }}><label>Deliver to<select name="addressId" required>
                            <option value="">Choose a saved address</option>
                            {addresses.map(a => <option value={a.id}
                                                        key={a.id}>{a.line1}, {a.city} {a.postal_code}</option>)}
                        </select></label><label>Promo code<input name="coupon" maxLength={40}
                                                                 placeholder="e.g. WELCOME10"/></label>
                            <button disabled={busy || !cart.length}>Calculate final total</button>
                        </form>
                        {quote && <>
                            <hr/>
                            {['subtotal', 'discount', 'shipping', 'tax', 'total'].filter(k => quote[k] !== undefined).map(k =>
                                <div className="total-row" key={k}><span>{k}</span><b>{money(quote[k])}</b></div>)}<p
                            className="subtle">{expired ? 'Quote expired. Calculate a new total.' : 'Quote valid for ' + Math.max(0, Math.ceil((new Date(quote.expires_at).getTime() - now) / 1000)) + ' seconds'}</p>
                            <button disabled={busy || !!expired} onClick={() => run(async () => {
                                const o = await api('/checkouts', 'POST', {quoteId: quote.id}, key.current);
                                setQuote(null);
                                setCart([]);
                                setOrderPage(0);
                                setNotice('Order placed. Payment status is available in your orders.');
                                nav('orders');
                                await openOrder(o.id)
                            })}>Place order · {money(quote.total)}</button>
                        </>}<p className="subtle">Sandbox environment. No real charges. Hosted test payment may be
                            required after placing an order.</p></section>
                </div>
            </>}
            {view === 'orders' && <>
                <div className="section-heading"><h1>Your orders<span className="dot">.</span></h1>
                    <button disabled={busy} onClick={() => run(async () => {
                        await load();
                        if (order) await openOrder(order.order.id)
                    })}>Refresh status
                    </button>
                </div>
                {!rows.length && !loading && <div className="empty">{empty}</div>}{rows.map(o => <article
                className="line" key={o.id}>
                <div><small>ORDER {o.id}</small><h3>{money(o.total)}</h3><span
                    className="badge">{o.status.replaceAll('_', ' ')}</span></div>
                <button disabled={busy} onClick={() => run(() => openOrder(o.id))}>Details & tracking →</button>
            </article>)}
                <div className="pagination">
                    <button disabled={!orderPage || loading || busy} onClick={() => {
                        setOrder(null);
                        setOrderPage(p => p - 1)
                    }}>Previous orders
                    </button>
                    <span>Orders page {orderPage + 1}</span>
                    <button disabled={rows.length < 20 || loading || busy} onClick={() => {
                        setOrder(null);
                        setOrderPage(p => p + 1)
                    }}>Older orders
                    </button>
                </div>
                {order && <section className="panel"><h2>Order details</h2><p>Order: <b>{order.order.status}</b> ·
                    Payment: <b>{order.payment.status}</b>
                </p>{['CREATED', 'AUTHORIZATION_PENDING', 'CAPTURE_PENDING'].includes(order.payment.status) &&
                    <button disabled={busy} onClick={() => run(async () => {
                        await api('/payments/' + order.payment.id + '/confirm', 'POST', {});
                        await openOrder(order.order.id)
                    })}>Refresh payment with
                        provider</button>}{order.payment.checkout_url && order.payment.status === 'CREATED' &&
                    <a className="button" href={safeUrl(order.payment.checkout_url)}>Continue secure test payment
                        →</a>}{['PAYMENT_PENDING', 'CONFIRMED'].includes(order.order.status) &&
                    <button disabled={busy} onClick={() => run(async () => {
                        await api('/orders/' + order.order.id + '/cancel', 'POST', {});
                        await openOrder(order.order.id);
                        await load()
                    })}>Cancel order</button>}{order.refunds.map((r: Row) => <p
                    key={r.id}>Refund: {money(r.amount)} · {r.status}</p>)}{order.items.map((i: Row) => <div
                    className="line" key={i.id}>
                    <div><h3>{i.title} × {i.quantity}</h3>
                        <p>{money(i.net)}</p>{order.shipments.filter((s: Row) => s.order_item_id === i.id).map((s: Row) =>
                            <div key={s.id}><p>{s.carrier} · {s.tracking} <span
                                className="badge">{s.status}</span> · {s.quantity} units</p>{s.status === 'DELIVERED' &&
                                <ReturnForm key={s.id} orderId={order.order.id} item={i} shipment={s} run={run}
                                            busy={busy} notify={setNotice}/>}</div>)}</div>
                </div>)}</section>}</>}
            {view === 'account' && <><h1>Your account</h1>
                <div className="checkout-grid">
                    <section className="panel"><Form title="Profile" fields={['displayName:Display name']} run={run}
                                                     busy={busy} submit={async d => {
                        await api('/customers/me', 'PATCH', {...d, active: true});
                        setUser(await api('/customers/me'));
                        setNotice('Profile updated')
                    }}/>
                        <button onClick={() => run(async () => {
                            const c = await api('/csrf');
                            await fetch('/auth/logout', {method: 'POST', headers: {[c.headerName]: c.token}});
                            setUser(null);
                            setCart([]);
                            setAddresses([]);
                            setQuote(null);
                            setOrder(null);
                            nav('shop')
                        })}>Sign out
                        </button>
                        <button className="plain" onClick={() => run(async () => {
                            await api('/sessions', 'DELETE');
                            requireLogin()
                        })}>Sign out all sessions
                        </button>
                    </section>
                    <section><AccountTools run={run} busy={busy}/><h2>Notifications</h2>{rows.length ? rows.map(r =>
                        <article className="panel" key={r.event_id || r.id}>
                            <h3>{r.subject || r.type || 'Order update'}</h3><p>{r.body || r.message || r.payload}</p>
                        </article>) : <p>{empty}</p>}</section>
                </div>
            </>}
            {['seller', 'admin'].includes(view) && <Workspace view={view} run={run} busy={busy} notify={setNotice}/>}
        </main>
        <footer>
            <div className="logo">market<span>lane</span>↗</div>
            <p>A little discovery goes a long way.</p><span>Marketplace sandbox · INR · No real charges</span><small>Availability
            is confirmed at checkout.</small></footer>
        <dialog ref={dialog} onCancel={() => setDetail(null)} onClick={e => {
            if (e.target === dialog.current) {
                dialog.current.close();
                setDetail(null)
            }
        }}>{detail && <>
            <button className="close" aria-label="Close product details" onClick={() => {
                dialog.current?.close();
                setDetail(null)
            }}>×
            </button>
            <span className="eyebrow">{detail.product.brand}</span>
            <h2>{detail.product.name}</h2>{detail.product.image_url && safeUrl(detail.product.image_url) !== "#" &&
            <img src={safeUrl(detail.product.image_url)} alt={detail.product.name}
                 style={{maxWidth: "100%", maxHeight: 280, objectFit: "contain"}} referrerPolicy="no-referrer"/>}
            <p>{detail.product.description}</p>{error &&
            <p className="alert" role="alert">{error}</p>}{detail.sellerProfile &&
            <p role="status">Seller: {detail.sellerProfile.name} · {detail.sellerProfile.status}</p>}<h3>Compare seller
            offers</h3>{detail.offers.map((o: Row) => <article className="line" key={o.id}>
            <div><h3>{o.seller_name}</h3><small>{o.sku} · {o.available} available</small>
                <p>{attributesText(o.attributes)}</p>
                <button className="plain" disabled={busy} onClick={() => run(async () => {
                    const a = await api('/offers/' + o.id + '/availability');
                    setDetail((d: Row | null) => d ? {
                        ...d,
                        offers: d.offers.map((v: Row) => v.id === o.id ? {...v, available: a.available} : v)
                    } : d)
                })}>Check current stock
                </button>
                <button className="plain" disabled={busy} onClick={() => run(async () => {
                    const seller = await api('/sellers/' + o.seller_id);
                    setDetail((d: Row | null) => d ? {...d, sellerProfile: seller} : d)
                })}>View seller profile
                </button>
                <p>{money(o.price)}</p></div>
            <button disabled={busy || o.available < 1} onClick={() => run(async () => {
                const current = await api('/cart');
                const existing = current.find((i: Row) => i.offer_id === o.id);
                const quantity = (existing?.quantity || 0) + 1;
                if (quantity > 100) throw new Error('You can add up to 100 units per offer.');
                await api('/cart/items', 'POST', {offerId: o.id, quantity});
                setQuote(null);
                await loadCart();
                setNotice('Added to your bag');
                dialog.current?.close();
                setDetail(null)
            })}>Add to bag +
            </button>
        </article>)}<h3>Reviews · {Number(detail.rating.average).toFixed(1)} / 5
            ({detail.rating.count})</h3>{detail.reviews.map((r: Row) => <blockquote key={r.id}>{'★'.repeat(r.stars)}
            <p>{r.body}</p></blockquote>)}
            <div className="pagination">
                <button disabled={!reviewPage || busy} onClick={() => run(() => reviewsPage(reviewPage - 1))}>Previous
                    reviews
                </button>
                <span>Reviews page {reviewPage + 1}</span>
                <button disabled={detail.reviews.length < 20 || busy}
                        onClick={() => run(() => reviewsPage(reviewPage + 1))}>More reviews
                </button>
            </div>
            <Form title="Review your delivered purchase" fields={['stars:Rating (1–5)', 'body:Your review']} busy={busy}
                  run={run} submit={async d => {
                await api('/products/' + detail.product.id + '/reviews', 'POST', {...d, stars: Number(d.stars)});
                await openProduct(detail.product.id);
                setNotice('Review saved')
            }}/>{detail.recs.length > 0 && <><h3>You might also like</h3>{detail.recs.map((r: Row) => <button
            className="plain" key={r.id}
            onClick={() => run(() => openProduct(r.id))}>{r.name} →</button>)}</>}</>}</dialog>
    </>
}

function ReturnForm({orderId, item, shipment, run, busy, notify}: any) {
    const key = useRef('');
    const [submitted, setSubmitted] = useState(false);
    if (submitted) return <p role="status">Return requested. Track refunds in your order details.</p>;
    return <form onSubmit={e => {
        e.preventDefault();
        const d = Object.fromEntries(new FormData(e.currentTarget));
        if (!key.current) key.current = crypto.randomUUID();
        run(async () => {
            const r = await api('/orders/' + orderId + '/returns', 'POST', {
                itemId: item.id,
                shipmentId: shipment.id,
                quantity: Number(d.quantity),
                reason: d.reason
            }, key.current);
            setSubmitted(true);
            notify('Return ' + r.id + ' · ' + r.status)
        })
    }}><label>Return quantity<input name="quantity" type="number" min="1" max={shipment.quantity} defaultValue="1"
                                    required/></label><label>Reason<input name="reason" maxLength={500}
                                                                          required/></label>
        <button disabled={busy}>Request return</button>
    </form>
}

function attributesText(value: unknown) {
    try {
        const raw = value as any;
        const attributes = typeof raw === 'string' ? JSON.parse(raw) : raw?.value ? JSON.parse(raw.value) : raw;
        if (!attributes || typeof attributes !== 'object') return '';
        return Object.entries(attributes).map(([k, v]) => (k === 'weightGrams' ? 'Weight (grams)' : k) + ': ' + String(v)).join(' · ');
    } catch {
        return ''
    }
}
