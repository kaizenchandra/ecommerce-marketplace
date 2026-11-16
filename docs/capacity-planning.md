# Capacity model (estimates, not measurements)

Inputs from the target: 100m customers, 1m sellers, 100m SKUs, 10m DAU, 1m peak browse RPS, 100k search RPS, 10k order/s
peak. Peak duration and average order rate are unknown. Example sizing below assumes 1k order/s average, 3 lines/order,
2 KiB SKU metadata, 4 KiB index document, 4 images/SKU at 200 KiB/image, 3 live offers/SKU, 512 B/offer+inventory, 20%
customers with a 10-line 2 KiB cart. Storage arithmetic excludes replication, index overhead and bloat unless stated.

| Resource                       | Formula                                                           | Illustrative logical size/rate                                  |
|--------------------------------|-------------------------------------------------------------------|-----------------------------------------------------------------|
| Canonical catalog              | 100m × 2 KiB                                                      | ~191 GiB                                                        |
| Offers/inventory               | 100m × 3 × 512 B                                                  | ~143 GiB before indexes/movements                               |
| Search                         | 100m × 4 KiB × 1.5 index overhead                                 | ~572 GiB primary, ~1.1 TiB with one replica                     |
| Media                          | 100m × 4 × 200 KiB                                                | ~74.5 TiB                                                       |
| Durable carts                  | 100m × .20 × 2 KiB                                                | ~38 GiB excluding indexes                                       |
| Orders/day                     | 1k/s × 86,400                                                     | 86.4m orders/day                                                |
| Order data/day                 | 86.4m × (1 KiB header + 3 × 512 B lines + 1 KiB workflow/payment) | ~288 GiB/day before indexes/replicas                            |
| Payment events                 | 1k avg order/s × 4 events                                         | 4k/s average; 40k/s at peak                                     |
| Event stream                   | 10k peak orders/s × 8 events × 1 KiB                              | ~78 MiB/s payload; × replication factor for broker I/O          |
| Redis product hot set (future) | 1m entries × 2 KiB × 1.5 overhead                                 | ~2.9 GiB plus replicas and headroom                             |
| Category cache (implemented)   | category count × document bytes                                   | measure actual JSON; current corpus is tiny                     |
| Inventory writes peak          | 10k orders/s × 3 lines × 2 reserve/commit writes                  | 60k logical inventory writes/s plus order/payment/outbox writes |
| Browse response bandwidth      | 1m/s × 10 KiB response                                            | ~9.5 GiB/s before compression                                   |
| Image CDN traffic              | browse/s × images/page × bytes/image × cache miss ratio           | CDN hit ratio dominates origin cost                             |

Order retention at 86m/day quickly dominates. Partition orders/events by time, route customer orders by
customer/home-region shard, retain lookup indirection by UUID, archive completed immutable history. Keep inventory
partitioned by seller/warehouse with one writer per allocation; a hot SKU still contends within its ownership shard.

Database IOPS cannot be inferred from logical writes: measure WAL bytes, buffer hit ratio, fsyncs, checkpoint spikes,
indexes, replication and device latency under the target dataset. Instance count = ceil(peak effective RPS / measured
sustainable RPS per instance at latency/error SLO), then include failure/headroom factor. Queue/connection concurrency
uses Little's law L=lambda×W; if a measured transaction takes W seconds, 10k/s needs about 10k×W active DB operations,
bounded by measured database service capacity. Virtual threads do not increase DB capacity. Default platform threads and
Hikari 16 are intentional for this sandbox.

1m browse RPS requires CDN, edge caching, partitioned read models and multiple regions. The monolith/PostgreSQL FTS
baseline is not expected to serve this corpus/traffic unchanged. Product/category cache-aside should use versioned keys,
TTL jitter, single-flight and small negative caching; invalidate by outbox. Availability/price hints remain
non-authoritative. Search requires OpenSearch at the stated scale, with asynchronous index version gating. Measured
performance is recorded separately in verification.md.
