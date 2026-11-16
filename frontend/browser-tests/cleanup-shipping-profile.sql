-- Local sandbox only: remove the shipping profile created by marketplace.spec.ts.
DELETE
FROM seller_shipping_profiles
WHERE seller_id = '30000000-0000-0000-0000-000000000001'
  AND address ->>'name' = 'Browser Store'
  AND address->>'street1' = '4 Test Street'
  AND address->>'city' = 'Pune'
  AND length_cm = 20
  AND width_cm = 15
  AND height_cm = 10
  AND max_weight_grams = 2000;
