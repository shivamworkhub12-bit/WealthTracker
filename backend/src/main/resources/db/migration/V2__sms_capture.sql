ALTER TABLE transactions ADD COLUMN reference VARCHAR(80);
ALTER TABLE transactions ADD COLUMN payment_method VARCHAR(20);
ALTER TABLE transactions ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
CREATE INDEX idx_transactions_reference ON transactions(reference) WHERE reference IS NOT NULL;

CREATE TABLE captured_alerts (
    id UUID PRIMARY KEY,
    event_key VARCHAR(64) NOT NULL UNIQUE,
    sender VARCHAR(100) NOT NULL,
    source VARCHAR(20) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL,
    date DATE,
    description VARCHAR(200),
    amount NUMERIC(14,2),
    type VARCHAR(10),
    account VARCHAR(80),
    reference VARCHAR(80),
    payment_method VARCHAR(20),
    suggested_category VARCHAR(80),
    notes TEXT NOT NULL,
    transaction_id UUID REFERENCES transactions(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_captured_alerts_created ON captured_alerts(created_at DESC);

CREATE TABLE capture_collector (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    state VARCHAR(30) NOT NULL,
    last_seen TIMESTAMPTZ NOT NULL,
    message VARCHAR(250) NOT NULL
);
