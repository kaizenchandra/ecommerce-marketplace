#!/usr/bin/env python3
"""Real HTTP session + CSRF + two-seller purchase, cancellation, and refund smoke."""
import http.cookiejar
import json
import os
import time
import urllib.parse
import urllib.request

base = os.environ.get('BASE_URL', 'http://localhost:8097')
jar = http.cookiejar.CookieJar();
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def call(path, body=None, method=None, key=None):
    headers = {'Content-Type': 'application/json'}
    if body is not None:
        headers[token['headerName']] = token['token']
    if key: headers['Idempotency-Key'] = key
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers,
                                 method=method)
    with client.open(req, timeout=10) as r:
        return json.load(r)


token = call('/api/v1/csrf')
login = urllib.parse.urlencode(
    {'username': 'customer', 'password': os.environ['LOCAL_PASSWORD'], '_csrf': token['token']}).encode()
client.open(urllib.request.Request(base + '/login', data=login), timeout=10).read()
token = call('/api/v1/csrf')
assert call('/api/v1/customers/me')['profile']['id'] == 'customer'
call('/api/v1/cart', {}, 'DELETE')
a = call('/api/v1/customers/me/addresses', {'line1': '1 Smoke Lane', 'city': 'Pune', 'postalCode': '411001'})
for n in [1, 2]: call('/api/v1/cart/items', {'offerId': f'40000000-0000-0000-0000-{n:012d}', 'quantity': 1})
q = call('/api/v1/checkout/quotes', {'addressId': a['id'], 'coupon': ''})
key = 'smoke-' + q['id'];
order = call('/api/v1/checkouts', {'quoteId': q['id']}, key=key)
assert call('/api/v1/checkouts', {'quoteId': q['id']}, key=key)['id'] == order['id']
for _ in range(30):
    detail = call('/api/v1/orders/' + order['id'])
    if detail['order']['status'] == 'CONFIRMED': break
    time.sleep(.5)
assert detail['order']['status'] == 'CONFIRMED', detail
assert len({i['seller_order_id'] for i in detail['items']}) == 2
assert sum(i['net'] for i in detail['items']) == detail['order']['total']
call('/api/v1/orders/' + order['id'] + '/cancel', {})
for _ in range(30):
    refunds = call('/api/v1/orders/' + order['id'] + '/refunds')
    if refunds and refunds[0]['status'] == 'COMPLETED': break
    time.sleep(.5)
assert len(refunds) == 1 and refunds[0]['status'] == 'COMPLETED', refunds
print(json.dumps({'result': 'PASS', 'orderId': order['id'], 'totalMinor': order['total'], 'sellerOrders': 2,
                  'refundStatus': refunds[0]['status']}))
