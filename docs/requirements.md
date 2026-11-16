# Scope and acceptance

This repository is a production-oriented local marketplace baseline, not an Amazon-scale production system. The master
specification remains the desired product scope; missing requirements are explicit in `limitations.md`. Local commerce
is INR, India addresses, tax-exempt test goods and free sandbox delivery. Tax and shipping values are snapshots with
zero values in this sandbox; they must not be used for live commerce.

Customers can browse canonical products, compare variants and seller offers, maintain durable carts/addresses, create
five-minute quotes, place multi-seller orders, inspect payment/shipment states, cancel unshipped orders, request
delivered-item returns and review delivered purchases. Sellers manage offers/prices/stock and whole-line shipments;
admins approve/suspend sellers, create/moderate catalog entries, simulate delivery, inspect returns and retry failed
workflows. Local accounts are development fixtures. External seller identity verification is not implemented.

Business invariants: inventory on_hand >= reserved >= 0; committed stock is deducted once; cart is not reservation;
accepted quote prices and line net allocations are immutable through application APIs; order total equals quote total
and sum of line nets; no negative money; currency INR and exact two-place input; quote rejection on price change; one
checkout per customer/key and per quote; one provider capture per order key; refund reservations include all pending,
manual and completed refunds; no refund may exceed captured amount; shipment and cancel serialize on order; inspection
restocks at most once; tenant operations must check ownership. A local SQL transaction can enforce cross-context
invariants because modules share one database; extraction removes that privilege.

Acceptance evidence is recorded in `verification.md`. Target NFRs (100m customers, 1m sellers, 100m SKUs, 99.99%
availability and regional p99 <200ms browse) are design objectives only. Local acceptance is real PostgreSQL
integration/concurrency checks and HTTP sandbox purchase/refund; it does not establish availability, compliance, or
disaster recovery.
