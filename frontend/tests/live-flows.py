#!/usr/bin/env python3
"""Live HTTP flows through Next.js into Spring; creates uniquely named sandbox fixtures."""
import http.cookiejar
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

BASE = os.environ.get('FRONTEND_URL', 'http://127.0.0.1:3000')
env = dict(line.split('=', 1) for line in (Path(__file__).resolve().parents[2] / '.env').read_text().splitlines() if
           line and not line.startswith('#'))
PASSWORD = os.environ.get('LOCAL_PASSWORD', env['LOCAL_PASSWORD'])
run_id = uuid.uuid4().hex[:10]
checks = []


def check(name, condition):
    assert condition, name
    checks.append(name)
    print('PASS:', name, flush=True)


class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, path, body=None, method='GET', key=None, expected=200):
        headers = {'Accept': 'application/json', 'Origin': BASE}
        if method != 'GET':
            token = self.call('/csrf')
            headers.update({'Content-Type': 'application/json', token['headerName']: token['token']})
        if key:
            headers['Idempotency-Key'] = key
        req = urllib.request.Request(BASE + '/api/v1' + path, data=None if body is None else json.dumps(body).encode(),
                                     method=method, headers=headers)
        try:
            r = self.opener.open(req, timeout=25)
        except urllib.error.HTTPError as e:
            r = e
        raw = r.read()
        assert r.status == expected, f'{method} {path}: {r.status} {raw[:500]!r}'
        return json.loads(raw) if raw else None

    def login(self, name, password=PASSWORD):
        token = self.call('/csrf')
        data = urllib.parse.urlencode({'username': name, 'password': password, '_csrf': token['token']}).encode()
        self.opener.open(urllib.request.Request(BASE + '/auth/login', data=data, headers={'Origin': BASE}),
                         timeout=25).read()
        return self.call('/customers/me')

    def logout(self):
        token = self.call('/csrf')
        self.opener.open(urllib.request.Request(BASE + '/auth/logout', data=b'',
                                                headers={'Origin': BASE, token['headerName']: token['token']})).read()


def poll(fn, condition, description):
    for _ in range(60):
        value = fn()
        if condition(value):
            check(description, True)
            return value
        time.sleep(.5)
    raise AssertionError(description + ': ' + json.dumps(value))


public = Client()
check('public catalog served through Next proxy', len(public.call('/products')) >= 2)
public.call('/cart', expected=401)
check('anonymous cart requires login', True)
customer, seller, admin = Client(), Client(), Client()
check('customer local form login', customer.login('customer2')['profile']['id'] == 'customer2')
check('seller local form login', seller.login('seller')['profile']['id'] == 'seller')
check('admin local form login', admin.login('admin')['profile']['id'] == 'admin')
customer.call('/admin/sellers', expected=403)
customer.call('/seller/offers', expected=403)
check('role authorization enforced through proxy', True)
check('test customer bag is empty before creating fixtures', not customer.call('/cart'))
# A unique product avoids altering pre-existing offers, orders or prices.
product = admin.call('/admin/products', {'name': 'E2E ' + run_id, 'brand': 'Verification',
                                         'description': 'Local sandbox verification fixture',
                                         'categoryId': 'electronics'}, 'POST')
variant = admin.call('/admin/products/' + product['id'] + '/variants',
                     {'sku': 'E2E-' + run_id, 'attributes': {'color': 'black'}}, 'POST')
profile = seller.call('/seller/me')
offer = seller.call('/sellers/' + profile['id'] + '/offers', {'variantId': variant['id'], 'price': '123.45'}, 'POST')
seller.call('/sellers/' + profile['id'] + '/inventory/adjustments',
            {'offerId': offer['id'], 'delta': 6, 'reason': 'E2E fixture stock'}, 'POST')
seller.call('/sellers/' + profile['id'] + '/offers/' + offer['id'], {'variantId': variant['id'], 'price': '125.50'},
            'PATCH')
check('admin catalog creation and seller offer/stock/price updates',
      any(o['id'] == offer['id'] and o['price'] == 12550 for o in seller.call('/seller/offers')))
check('customer search finds live seller offer',
      any(p['id'] == product['id'] for p in public.call('/products?q=' + urllib.parse.quote(run_id))))
admin.call('/admin/products/' + product['id'], {'status': 'HIDDEN'}, 'PATCH')
check('admin product moderation removes public listing', not public.call('/products?q=' + run_id))
admin.call('/admin/products/' + product['id'], {'status': 'ACTIVE'}, 'PATCH')
# Do not clear any existing customer bag; add only this unique fixture.
cart = customer.call('/cart/items', {'offerId': offer['id'], 'quantity': 1}, 'POST')
item = next(i for i in cart if i['offer_id'] == offer['id'])
customer.call('/cart/items/' + item['id'], {'quantity': 2}, 'PATCH')
# Quote covers whole cart; this fresh sandbox should contain this fixture only.
check('customer cart quantity update', len(customer.call('/cart')) == 1)
address = customer.call('/customers/me/addresses',
                        {'line1': '1 E2E Lane', 'city': 'Pune', 'postalCode': '411001', 'state': 'MH'}, 'POST')
quote = customer.call('/checkout/quotes', {'addressId': address['id'], 'coupon': ''}, 'POST')
check('authoritative quote total and expiry', quote['total'] == 25100 and bool(quote.get('expires_at')))
key = 'e2e-' + run_id
order = customer.call('/checkouts', {'quoteId': quote['id']}, 'POST', key)
check('checkout replay returns same order',
      customer.call('/checkouts', {'quoteId': quote['id']}, 'POST', key)['id'] == order['id'])
detail = poll(lambda: customer.call('/orders/' + order['id']), lambda d: d['order']['status'] == 'CONFIRMED',
              'payment simulator confirms customer order')
check('checkout clears durable cart', not customer.call('/cart'))
item = detail['items'][0]
group = next(g for g in seller.call('/seller/orders') if g['item_id'] == item['id'])
shipment = seller.call('/seller-orders/' + group['id'] + '/shipments',
                       {'itemId': item['id'], 'quantity': 1, 'carrier': 'local', 'tracking': 'E2E-' + run_id}, 'POST',
                       key + '-ship')
check('partial seller shipment visible to customer',
      customer.call('/orders/' + order['id'] + '/shipments')[0]['quantity'] == 1)
admin.call('/admin/shipments/' + shipment['id'] + '/deliver', {}, 'POST')
check('admin delivery visible to customer',
      customer.call('/orders/' + order['id'] + '/shipments')[0]['status'] == 'DELIVERED')
customer.call('/products/' + product['id'] + '/reviews', {'stars': 5, 'body': 'Verified local E2E purchase'}, 'POST')
check('delivered customer review is public',
      public.call('/products/' + product['id'] + '/reviews')['rating']['count'] == 1)
returned = customer.call('/orders/' + order['id'] + '/returns',
                         {'itemId': item['id'], 'shipmentId': shipment['id'], 'quantity': 1, 'reason': 'E2E return'},
                         'POST', key + '-return')
check('admin sees customer return', any(r['id'] == returned['id'] for r in admin.call('/admin/returns')))
admin.call('/admin/returns/' + returned['id'] + '/inspect', {'accepted': True, 'restock': True}, 'POST')
refunds = poll(lambda: customer.call('/orders/' + order['id'] + '/refunds'),
               lambda rows: bool(rows) and rows[0]['status'] == 'COMPLETED', 'accepted return completes partial refund')
check('partial refund equals one purchased unit', refunds[0]['amount'] == 12550)
# Complete remaining quantity through the label workflow.
label = seller.call('/seller-orders/' + group['id'] + '/labels',
                    {'itemId': item['id'], 'quantity': 1, 'maxCostMinor': 10000}, 'POST', key + '-label')
labels = poll(lambda: seller.call('/seller/labels'),
              lambda rows: any(l['id'] == label['id'] and l['status'] == 'PURCHASED' for l in rows),
              'seller sandbox label purchased')
seller.call('/shipping-labels/' + label['id'] + '/refresh', {}, 'POST')
seller.call('/shipping-labels/' + label['id'] + '/dispatch', {}, 'POST')
label_row = next(l for l in seller.call('/seller/labels') if l['id'] == label['id'])
document = seller.opener.open(BASE + label_row['label_url'], timeout=25).read().decode()
check('authenticated simulator label downloads through frontend', 'SIMULATOR LABEL' in document)
check('seller label dispatch visible',
      any(l['id'] == label['id'] and l['status'] == 'DISPATCHED' for l in seller.call('/seller/labels')))
for path in ['/admin/status', '/admin/tax-reporting', '/admin/shipping-labels']:
    admin.call(path)
check('admin operational reports load', True)
# A second order exercises cancellation after captured payment.
customer.call('/cart/items', {'offerId': offer['id'], 'quantity': 1}, 'POST')
cancel_quote = customer.call('/checkout/quotes', {'addressId': address['id'], 'coupon': ''}, 'POST')
cancelled = customer.call('/checkouts', {'quoteId': cancel_quote['id']}, 'POST', key + '-cancel')
poll(lambda: customer.call('/orders/' + cancelled['id']), lambda d: d['order']['status'] == 'CONFIRMED',
     'second order confirms before cancellation')
customer.call('/orders/' + cancelled['id'] + '/cancel', {}, 'POST')
poll(lambda: customer.call('/orders/' + cancelled['id'] + '/refunds'),
     lambda rows: bool(rows) and rows[0]['status'] == 'COMPLETED', 'customer cancellation completes refund')
check('customer notification inbox loads', isinstance(customer.call('/notifications'), list))
check('customer session list loads', len(customer.call('/sessions')) > 0)
customer.logout()
customer.call('/cart', expected=401)
check('customer logout invalidates authentication', True)
report = {'result': 'PASS', 'checks': len(checks), 'frontend': BASE, 'orderId': order['id'], 'productId': product['id'],
          'returnId': returned['id'], 'checksPassed': checks}
Path(__file__).with_name('live-results.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report), flush=True)
