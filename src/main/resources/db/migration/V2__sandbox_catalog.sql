INSERT INTO categories
VALUES ('electronics', 'Electronics'),
       ('books', 'Books');
INSERT INTO products(id, category_id, name, brand, description)
VALUES ('10000000-0000-0000-0000-000000000001', 'electronics', 'Studio Headphones', 'Acme',
        'Comfortable wired headphones'),
       ('10000000-0000-0000-0000-000000000002', 'books', 'Distributed Systems', 'Learning Press',
        'A practical introduction');
INSERT INTO product_variants(id, product_id, sku, attributes)
VALUES ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'HEADPHONE-BLACK', '{
  "color": "black"
}'),
       ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'BOOK-PAPER', '{
         "format": "paperback"
       }');
INSERT INTO sellers(id, owner_id, name, status)
VALUES ('30000000-0000-0000-0000-000000000001', 'seller', 'Acme Store', 'ACTIVE'),
       ('30000000-0000-0000-0000-000000000002', 'seller2', 'Book House', 'ACTIVE');
INSERT INTO seller_offers(id, variant_id, seller_id, price)
VALUES ('40000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
        '30000000-0000-0000-0000-000000000001', 299900),
       ('40000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002',
        '30000000-0000-0000-0000-000000000002', 79900),
       ('40000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000001',
        '30000000-0000-0000-0000-000000000002', 309900);
INSERT INTO inventory(offer_id, on_hand)
SELECT id, 100
FROM seller_offers;
INSERT INTO promotions(code, percent, max_uses, expires_at)
VALUES ('WELCOME10', 10, 100, now() + interval '365 days');
