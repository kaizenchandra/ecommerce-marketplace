# ADR 0001: PostgreSQL modular monolith and capture-only sandbox

Accepted. Start with Java 21/Spring Boot 3.5.16 MVC/JDBC and PostgreSQL conditional stock updates. Keep customer
checkout local to one DB transaction; call external payment port outside transactions and persist durable workflow
state. Capture-only simulator matches actual implemented payment lifecycle and demonstrates provider idempotency with
independent durable transactions. Prefer correctness and inspectable SQL over speculative service frameworks.

Use durable PostgreSQL carts; Redis failure cannot lose them. PostgreSQL FTS avoids premature OpenSearch operation.
Redis category cache is optional and has safe fallback. Local outbox/inbox gives durable notifications without deploying
Kafka to transport messages between code sharing the same DB. Kafka/OpenSearch/MinIO are not falsely presented as
working components. Static same-origin UI reduces security/deployment surface. Platform threads and bounded pool first;
evaluate virtual threads only with measured blocking I/O and database wait evidence.

Consequences: one database is the throughput/failure boundary. Package isolation and external adapters need
strengthening before extraction. This is a functional sandbox baseline with documented production gaps, not evidence of
Amazon scale or financial compliance.
