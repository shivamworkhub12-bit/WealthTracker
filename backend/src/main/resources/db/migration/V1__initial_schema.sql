CREATE TABLE categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(80) NOT NULL UNIQUE,
    color VARCHAR(20) NOT NULL DEFAULT '#64748B',
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE'))
);

CREATE TABLE transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    date DATE NOT NULL,
    description VARCHAR(200) NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    type VARCHAR(10) NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    category_id UUID REFERENCES categories(id) ON DELETE SET NULL,
    account VARCHAR(80),
    fingerprint VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_transactions_date ON transactions(date DESC);
CREATE INDEX idx_transactions_category ON transactions(category_id);

INSERT INTO categories(name,color,kind) VALUES
 ('Food & dining','#F97316','EXPENSE'), ('Transport','#3B82F6','EXPENSE'),
 ('Housing','#8B5CF6','EXPENSE'), ('Shopping','#EC4899','EXPENSE'),
 ('Utilities','#14B8A6','EXPENSE'), ('Health','#22C55E','EXPENSE'),
 ('Salary','#10B981','INCOME'), ('Other','#64748B','EXPENSE');
