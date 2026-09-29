-- Scenario 10: NiFi writes orders here (PutDatabaseRecord) and polls them back (QueryDatabaseTableRecord).
CREATE TABLE orders (
    id         SERIAL PRIMARY KEY,
    order_id   TEXT NOT NULL,
    customer   TEXT,
    amount     NUMERIC(12, 2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
