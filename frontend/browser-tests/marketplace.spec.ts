import {expect, Locator, Page, test} from '@playwright/test';
import {readFileSync} from 'node:fs';

test.describe.configure({mode: 'serial'});
const env = Object.fromEntries(readFileSync('../.env', 'utf8').split('\n').filter(l => l && !l.startsWith('#')).map(l => l.split(/=(.*)/s).slice(0, 2)));
let latestProductName = '';
const password = process.env.LOCAL_PASSWORD || env.LOCAL_PASSWORD;

function form(page: Page, title: string) {
    return page.locator('form').filter({has: page.getByRole('heading', {name: title, exact: true})})
}

async function fill(f: Locator, values: Record<string, string>) {
    for (const [name, value] of Object.entries(values)) await f.getByLabel(name, {exact: true}).fill(value)
}

async function submit(page: Page, f: Locator, button: string, path: string, method = 'POST') {
    const response = page.waitForResponse(r => r.url().includes('/api/v1/') && r.url().includes(path) && r.request().method() === method, {timeout: 12000});
    await f.getByRole('button', {name: button, exact: true}).click();
    const r = await response;
    expect(r.ok(), await r.text()).toBeTruthy();
    await expect(page.getByRole('button', {name: 'Working…', exact: true})).toHaveCount(0);
    return r.json();
}

async function login(page: Page, user: string) {
    await page.goto('/');
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    await page.getByLabel('Username', {exact: true}).fill(user);
    await page.getByLabel('Password', {exact: true}).fill(password);
    await page.getByRole('button', {name: 'Sign in →', exact: true}).click();
    await expect(page.getByRole('button', {name: new RegExp('Account: ' + user)})).toBeVisible();
}

async function refresh(page: Page) {
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    await expect(page.getByRole('button', {name: 'Refresh', exact: true})).toBeEnabled()
}

test('customer → seller → admin → return/refund using browser UI', async ({browser}, testInfo) => {
    const errors: string[] = [];
    const contexts = await Promise.all([browser.newContext(), browser.newContext(), browser.newContext()]);
    const [customer, seller, admin] = await Promise.all(contexts.map(c => c.newPage()));
    for (const p of [customer, seller, admin]) p.on('pageerror', e => errors.push(e.message));
    const name = 'Browser ' + Date.now(), sku = 'UI-' + Date.now(), tracking = 'TRACK-' + Date.now();
    let product: any, variant: any, order: any, returned: any, labelId = '';
    latestProductName = name;
    await test.step('admin creates product and SKU using forms', async () => {
        console.log('admin creates product and SKU using forms');
        await login(admin, 'admin');
        await admin.getByRole('button', {name: 'Operations', exact: true}).click();
        let f = form(admin, 'Create product');
        await fill(f, {
            'Product name': name,
            'Brand': 'UI Verification',
            'Description': 'Created through browser forms',
            'Category ID': 'electronics'
        });
        product = await submit(admin, f, 'Create product', '/admin/products');
        f = form(admin, 'Create variant');
        await fill(f, {
            'Product UUID': product.id,
            'SKU': sku,
            'Color': 'black',
            'Weight (grams)': '500',
            'Additional attributes (JSON)': '{"material":"cotton"}'
        });
        variant = await submit(admin, f, 'Create variant', '/variants');
    });
    await test.step('seller creates offer, adds stock, and updates price', async () => {
        console.log('seller creates offer, adds stock, and updates price');
        await login(seller, 'seller');
        await seller.getByRole('button', {name: 'Seller desk', exact: true}).click();
        const shipping = form(seller, 'Save shipping profile');
        await fill(shipping, {
            'Sender name': 'Browser Store',
            'Origin street address': '4 Test Street',
            'Origin city': 'Pune',
            'Origin postal code': '411001',
            'Country code (e.g. IN)': 'IN',
            'Length (cm)': '20',
            'Width (cm)': '15',
            'Height (cm)': '10',
            'Maximum weight (grams)': '2000'
        });
        await submit(seller, shipping, 'Save shipping profile', '/shipping-profile', 'PUT');
        await expect(seller.getByRole('status').filter({hasText: 'Shipping profile saved'})).toBeVisible();
        let f = form(seller, 'Create offer');
        await fill(f, {'Variant UUID': variant.id, 'Price (INR)': '125.50'});
        await submit(seller, f, 'Create offer', '/offers');
        const card = seller.locator('article').filter({has: seller.getByRole('heading', {name: sku, exact: true})});
        f = card.locator('form').filter({has: seller.getByRole('heading', {name: 'Adjust stock', exact: true})});
        await fill(f, {'Units to add or subtract': '5', 'Adjustment reason': 'Browser verification'});
        await submit(seller, f, 'Adjust stock', '/inventory/adjustments');
        await expect(card).toContainText('5 on hand');
        f = card.locator('form').filter({has: seller.getByRole('heading', {name: 'Save price', exact: true})});
        await fill(f, {'New price (INR)': '129.50'});
        await submit(seller, f, 'Save price', '/offers/', 'PATCH');
        await expect(card).toContainText('₹129.50');
    });
    await test.step('customer browses, compares and adds an existing offer twice', async () => {
        console.log('customer browses, compares and adds an existing offer twice');
        await login(customer, 'customer2');
        await customer.getByRole('button', {name: /^Bag /}).click();
        const clear = customer.getByRole('button', {name: 'Clear bag', exact: true});
        if (await clear.isVisible()) {
            await clear.click();
            await expect(customer.getByRole('button', {name: 'Bag 0', exact: true})).toBeVisible()
        }
        await customer.getByRole('button', {name: 'Discover', exact: true}).click();
        await customer.getByRole('combobox', {name: 'Search products'}).fill(name);
        const card = customer.getByRole('button').filter({has: customer.getByRole('heading', {name, exact: true})});
        await card.click();
        await expect(customer.getByRole('dialog')).toContainText('Acme Store');
        await customer.getByRole('dialog').getByRole('button', {name: 'Check current stock', exact: true}).click();
        await customer.getByRole('dialog').getByRole('button', {name: 'View seller profile', exact: true}).click();
        await expect(customer.getByRole('dialog').getByRole('status').filter({hasText: 'Acme Store · ACTIVE'})).toBeAttached();
        await expect(customer.getByRole('button', {name: 'Previous reviews', exact: true})).toBeDisabled();
        await expect(customer.getByRole('button', {name: 'More reviews', exact: true})).toBeDisabled();
        await customer.getByRole('dialog').getByRole('button', {name: 'Add to bag +'}).click();
        await expect(customer.getByRole('dialog')).not.toBeVisible();
        await card.click();
        await customer.getByRole('dialog').getByRole('button', {name: 'Add to bag +'}).click();
        await expect(customer.getByRole('button', {name: 'Bag 2', exact: true})).toBeVisible();
        await customer.getByRole('button', {name: 'Bag 2', exact: true}).click();
        await expect(customer.getByRole('spinbutton', {name: 'Quantity', exact: true})).toHaveValue('2');
    });
    await test.step('customer saves address, calculates quote and places order', async () => {
        console.log('customer saves address, calculates quote and places order');
        let f = form(customer, 'Save a new address');
        await fill(f, {
            'Street address': '3 Browser Lane',
            'City': 'Pune',
            'Postal code': '411001',
            'State code (e.g. MH)': 'MH'
        });
        const address = await submit(customer, f, 'Save a new address', '/customers/me/addresses');
        await customer.getByRole('combobox', {name: /Deliver to/}).selectOption(address.id);
        f = customer.locator('form').filter({
            has: customer.getByRole('button', {
                name: 'Calculate final total',
                exact: true
            })
        });
        const quote = await submit(customer, f, 'Calculate final total', '/checkout/quotes');
        expect(quote.total).toBe(25900);
        const response = customer.waitForResponse(r => r.url().endsWith('/api/v1/checkouts') && r.request().method() === 'POST');
        await customer.getByRole('button', {name: 'Place order · ₹259.00', exact: true}).click();
        order = await (await response).json();
        await expect(customer.getByRole('heading', {name: 'Order details', exact: true})).toBeVisible();
        const providerRefresh = customer.getByRole('button', {name: 'Refresh payment with provider', exact: true});
        if (await providerRefresh.isVisible()) {
            const response = customer.waitForResponse(r => r.url().includes('/payments/') && r.url().endsWith('/confirm'));
            await providerRefresh.click();
            expect((await response).ok()).toBeTruthy()
        }
        await expect(async () => {
            await customer.getByRole('button', {name: 'Refresh status', exact: true}).click();
            await expect(customer.getByText('CAPTURED', {exact: true})).toBeVisible()
        }).toPass({timeout: 30000});
        await customer.screenshot({path: testInfo.outputPath('customer-order.png'), fullPage: true});
    });
    await test.step('seller records a partial shipment and sees remaining quantity reset', async () => {
        console.log('seller records a partial shipment and sees remaining quantity reset');
        await refresh(seller);
        const card = seller.locator('article').filter({
            has: seller.getByRole('heading', {
                name: name + ' × 2',
                exact: true
            })
        });
        const f = card.locator('form').filter({
            has: seller.getByRole('button', {
                name: 'Record shipment',
                exact: true
            })
        });
        await fill(f, {'Quantity': '1', 'Carrier': 'local', 'Tracking number': tracking});
        await submit(seller, f, 'Record shipment', '/shipments');
        await expect(card).toContainText('1 shipped');
        await expect(f.getByLabel('Quantity', {exact: true})).toHaveValue('1');
        await seller.screenshot({path: testInfo.outputPath('seller-fulfillment.png'), fullPage: true});
    });
    await test.step('admin simulates delivery', async () => {
        console.log('admin simulates delivery');
        await refresh(admin);
        const card = admin.locator('article').filter({has: admin.getByRole('heading', {name: tracking, exact: true})});
        await card.getByRole('button', {name: 'Simulate delivery', exact: true}).click();
        await expect(card).toContainText('DELIVERED');
    });
    await test.step('customer reviews delivered product and requests partial return', async () => {
        console.log('customer reviews delivered product and requests partial return');
        await customer.getByRole('button', {name: 'Refresh status', exact: true}).click();
        await expect(customer.getByText('DELIVERED', {exact: true})).toBeVisible();
        const f = customer.locator('form').filter({
            has: customer.getByRole('button', {
                name: 'Request return',
                exact: true
            })
        });
        await fill(f, {'Return quantity': '1', 'Reason': 'Browser verification return'});
        returned = await submit(customer, f, 'Request return', '/returns');
        await customer.getByRole('button', {name: 'Discover', exact: true}).click();
        await customer.getByRole('button').filter({has: customer.getByRole('heading', {name, exact: true})}).click();
        const review = form(customer, 'Review your delivered purchase');
        await fill(review, {'Rating (1–5)': '5', 'Your review': 'Purchased and reviewed via browser'});
        await submit(customer, review, 'Review your delivered purchase', '/reviews');
        await expect(customer.getByRole('dialog')).toContainText('Purchased and reviewed via browser');
        await customer.getByRole('button', {name: 'Close product details', exact: true}).click();
    });
    await test.step('admin accepts return; customer sees completed refund', async () => {
        console.log('admin accepts return; customer sees completed refund');
        await refresh(admin);
        const card = admin.locator('article').filter({hasText: returned.id});
        await card.getByRole('button', {name: 'Accept & restock', exact: true}).click();
        await expect(card).toContainText('RECEIVED');
        await admin.screenshot({path: testInfo.outputPath('admin-return.png'), fullPage: true});
        await customer.getByRole('button', {name: 'Your orders', exact: true}).click();
        const orderCard = customer.locator('article').filter({hasText: order.id});
        await orderCard.getByRole('button', {name: 'Details & tracking →', exact: true}).click();
        await expect(async () => {
            await customer.getByRole('button', {name: 'Refresh status', exact: true}).click();
            await expect(customer.getByText('Refund: ₹129.50 · COMPLETED', {exact: true})).toBeVisible()
        }).toPass({timeout: 30000});
    });
    await test.step('customer tracks the return and inspects sessions/address book', async () => {
        await customer.getByRole('button', {name: 'Account: customer2', exact: true}).click();
        await expect(customer.getByRole('heading', {name: 'Active sessions', exact: true})).toBeVisible();
        await expect(customer.getByText(/3 Browser Lane, Pune, 411001/).first()).toBeVisible();
        const tracker = form(customer, 'Track a return');
        await fill(tracker, {'Return ID': returned.id});
        await submit(customer, tracker, 'Track a return', '/returns/', 'GET');
        await expect(customer.getByRole('article', {name: 'Return status'})).toContainText('REFUNDED');
        await customer.screenshot({path: testInfo.outputPath('account-return-status.png'), fullPage: true});
        await customer.getByRole('button', {name: 'Your orders', exact: true}).click();
        if (await customer.getByRole('button', {name: 'Older orders', exact: true}).isEnabled()) {
            await customer.getByRole('button', {name: 'Older orders', exact: true}).click();
            await expect(customer.getByText('Orders page 2', {exact: true})).toBeVisible();
            await customer.getByRole('button', {name: 'Previous orders', exact: true}).click();
        }
        await expect(customer.getByText('Orders page 1', {exact: true})).toBeVisible();
    });
    await test.step('seller buys label, opens authenticated document, and dispatches remaining unit', async () => {
        console.log('seller buys label, opens authenticated document, and dispatches remaining unit');
        await refresh(seller);
        const card = seller.locator('article').filter({
            has: seller.getByRole('heading', {
                name: name + ' × 2',
                exact: true
            })
        });
        const f = card.locator('form').filter({
            has: seller.getByRole('button', {
                name: 'Buy test shipping label',
                exact: true
            })
        });
        const label = await submit(seller, f, 'Buy test shipping label', '/labels');
        labelId = label.id;
        const labelCard = seller.locator('article').filter({
            has: seller.getByRole('heading', {
                name: 'Label ' + label.id,
                exact: true
            })
        });
        await expect(async () => {
            await refresh(seller);
            await expect(labelCard.getByRole('button', {name: 'Confirm physical dispatch', exact: true})).toBeVisible()
        }).toPass({timeout: 30000});
        const popup = seller.waitForEvent('popup');
        await labelCard.getByRole('link', {name: 'Download label ↗', exact: true}).click();
        const doc = await popup;
        await expect(doc.locator('body')).toContainText('SIMULATOR LABEL');
        await doc.close();
        await labelCard.getByRole('button', {name: 'Confirm physical dispatch', exact: true}).click();
        await expect(card).toContainText('2 shipped');
    });
    await test.step('mobile catalog layout and logout', async () => {
        console.log('mobile catalog layout and logout');
        await customer.setViewportSize({width: 390, height: 844});
        await customer.getByRole('button', {name: 'Discover', exact: true}).click();
        await expect(customer.getByRole('heading', {
            name: 'Good finds. Great little discoveries.',
            exact: true
        })).toBeVisible();
        expect(await customer.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
        await customer.screenshot({path: testInfo.outputPath('mobile-catalog.png'), fullPage: true});
        await customer.getByRole('button', {name: 'Account: customer2', exact: true}).click();
        await customer.getByRole('button', {name: 'Sign out', exact: true}).click();
        await expect(customer.getByRole('button', {name: 'Sign in', exact: true})).toBeVisible();
    });
    await test.step('admin inspects recovery queues, submits workflow retry and sees purchase safeguards', async () => {
        await expect(admin.getByRole('heading', {name: 'Recovery desk', exact: true})).toBeVisible();
        await admin.getByRole('button', {name: 'Refresh recovery queues', exact: true}).click();
        await expect(admin.getByRole('heading', {name: 'Payment webhook events', exact: true})).toBeVisible();
        const retry = form(admin, 'Retry checkout workflow');
        await fill(retry, {'Order ID': order.id});
        await submit(admin, retry, 'Retry checkout workflow', '/workflows/');
        await expect(admin.getByRole('status').filter({hasText: 'Recovery request accepted'})).toBeVisible();
        const abort = form(admin, 'Abort unattempted shipping purchase');
        await fill(abort, {'Shipping label ID': labelId});
        const rejection = admin.waitForResponse(r => r.url().endsWith('/shipping-labels/' + labelId + '/abort') && r.request().method() === 'POST');
        await abort.getByRole('button', {name: 'Abort unattempted shipping purchase', exact: true}).click();
        expect((await rejection).status()).toBe(409);
        await expect(admin.getByRole('alert').filter({hasText: 'requires provider resolution'})).toBeVisible();
        await admin.screenshot({path: testInfo.outputPath('admin-recovery.png'), fullPage: true});
    });
    await test.step('seller and admin sign out through account screens', async () => {
        for (const [page, user] of [[seller, 'seller'], [admin, 'admin']] as const) {
            await page.getByRole('button', {name: 'Account: ' + user, exact: true}).click();
            await page.getByRole('button', {name: 'Sign out', exact: true}).click();
            await expect(page.getByRole('button', {name: 'Sign in', exact: true})).toBeVisible();
        }
    });
    expect(errors).toEqual([]);
    for (const c of contexts) await c.close();
});

test('expired session closes product dialog; login errors remain recoverable', async ({page, context}) => {
    await login(page, 'customer2');
    await page.getByRole('button').filter({
        has: page.getByRole('heading', {
            name: 'Studio Headphones',
            exact: true
        })
    }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await context.clearCookies();
    await page.getByRole('dialog').getByRole('button', {name: 'Add to bag +'}).first().click();
    await expect(page.getByRole('dialog')).not.toBeVisible();
    await expect(page.getByLabel('Username', {exact: true})).toBeVisible();
    await page.getByLabel('Username', {exact: true}).fill('customer2');
    await page.getByLabel('Password', {exact: true}).fill('invalid-test-password');
    await page.getByRole('button', {name: 'Sign in →', exact: true}).click();
    await expect(page.getByRole('alert').filter({hasText: 'Sign-in failed'})).toBeVisible();
    await page.getByLabel('Password', {exact: true}).fill(password);
    await page.getByRole('button', {name: 'Sign in →', exact: true}).click();
    await expect(page.getByRole('button', {name: 'Account: customer2', exact: true})).toBeVisible();
});

test('customer cancels a paid sandbox order and sees completed refund', async ({page}) => {
    await login(page, 'customer2');
    await page.getByRole('button').filter({
        has: page.getByRole('heading', {
            name: latestProductName,
            exact: true
        })
    }).click();
    await page.getByRole('dialog').getByRole('button', {name: 'Add to bag +'}).first().click();
    await expect(page.getByRole('dialog')).not.toBeVisible();
    await page.getByRole('button', {name: 'Bag 1', exact: true}).click();
    const select = page.getByRole('combobox', {name: /Deliver to/});
    const value = await select.locator('option').last().getAttribute('value');
    await select.selectOption(value!);
    const quoteForm = page.locator('form').filter({
        has: page.getByRole('button', {
            name: 'Calculate final total',
            exact: true
        })
    });
    await submit(page, quoteForm, 'Calculate final total', '/checkout/quotes');
    await page.getByRole('button', {name: /^Place order ·/}).click();
    await expect(page.getByRole('heading', {name: 'Order details', exact: true})).toBeVisible();
    await expect(async () => {
        await page.getByRole('button', {name: 'Refresh status', exact: true}).click();
        await expect(page.getByText('CAPTURED', {exact: true})).toBeVisible()
    }).toPass({timeout: 30000});
    await page.getByRole('button', {name: 'Cancel order', exact: true}).click();
    await expect(async () => {
        await page.getByRole('button', {name: 'Refresh status', exact: true}).click();
        await expect(page.getByText(/Refund: .*COMPLETED/)).toBeVisible()
    }).toPass({timeout: 30000});
});
