#!/usr/bin/env python3
"""Offline HTTP label purchase -> physical dispatch -> two partial returns. No real postage."""
import http.cookiejar
import json
import os
import time
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get('BASE_URL', 'http://localhost:8097')


class Session:
    def __init__(self, user):
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.token = self.call('/csrf')
        form = urllib.parse.urlencode(
            {'username': user, 'password': os.environ['LOCAL_PASSWORD'], '_csrf': self.token['token']}).encode()
        self.client.open(urllib.request.Request(BASE + '/login', data=form), timeout=10).read()
        self.token = self.call('/csrf')

    def call(self, path, body=None, method=None, key=None):
        headers = {'Content-Type': 'application/json'}
        if body is not None: headers[self.token['headerName']] = self.token['token']
        if key: headers['Idempotency-Key'] = key
        request = urllib.request.Request(BASE + '/api/v1' + path,
                                         data=None if body is None else json.dumps(body).encode(), headers=headers,
                                         method=method)
        with self.client.open(request, timeout=10) as response:
            if response.headers.get_content_type() == 'text/plain': return response.read().decode()
            return json.load(response)


def until(read, predicate):
    for _ in range(60):
        value = read()
        if predicate(value): return value
        time.sleep(.5)
    raise AssertionError(value)


customer = Session('customer');
seller = Session('seller');
admin = Session('admin')
customer.call('/customers/me');
customer.call('/cart', {}, 'DELETE')
address = customer.call('/customers/me/addresses',
                        {'line1': '1 Label Smoke Lane', 'city': 'Pune', 'postalCode': '411001'})
customer.call('/cart/items', {'offerId': '40000000-0000-0000-0000-000000000001', 'quantity': 2})
quote = customer.call('/checkout/quotes', {'addressId': address['id']})
order = customer.call('/checkouts', {'quoteId': quote['id']}, key='label-smoke-' + str(uuid.uuid4()))
detail = until(lambda: customer.call('/orders/' + order['id']), lambda value: value['order']['status'] == 'CONFIRMED')
item = detail['items'][0];
body = {'itemId': item['id'], 'quantity': 2, 'maxCostMinor': 0};
key = 'label-' + str(uuid.uuid4())
label = seller.call('/seller-orders/' + item['seller_order_id'] + '/labels', body, key=key)
assert seller.call('/seller-orders/' + item['seller_order_id'] + '/labels', body, key=key)['id'] == label['id']
label = until(lambda: seller.call('/shipping-labels/' + label['id']), lambda value: value['status'] == 'PURCHASED')
assert label['provider_id'].startswith('local_'), 'This smoke is only for the local simulator'
assert 'NOT VALID POSTAGE' in seller.call('/shipping-labels/' + label['id'] + '/document')
shipment = seller.call('/shipping-labels/' + label['id'] + '/dispatch', {})
assert seller.call('/shipping-labels/' + label['id'] + '/dispatch', {})['id'] == shipment['id']
admin.call('/admin/shipments/' + shipment['id'] + '/deliver', {})
for n in range(2):
    returned = customer.call('/orders/' + order['id'] + '/returns',
                             {'itemId': item['id'], 'shipmentId': shipment['id'], 'quantity': 1,
                              'reason': 'Label smoke partial return'}, key='return-' + str(uuid.uuid4()))
    admin.call('/admin/returns/' + returned['id'] + '/inspect', {'accepted': True, 'restock': True})
refunds = until(lambda: customer.call('/orders/' + order['id'] + '/refunds'),
                lambda value: len(value) == 2 and all(row['status'] == 'COMPLETED' for row in value))
assert sum(row['amount'] for row in refunds) == order['total']
print(json.dumps({'result': 'PASS', 'orderId': order['id'], 'labelId': label['id'], 'labelPurchases': 1, 'shipments': 1,
                  'partialRefunds': 2, 'refundedMinor': sum(row['amount'] for row in refunds)}))
