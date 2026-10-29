CREATE TABLE gmail_connection (
    id INTEGER PRIMARY KEY CHECK (id=1),
    generation UUID NOT NULL,
    email VARCHAR(254) NOT NULL,
    encrypted_refresh_token TEXT NOT NULL,
    granted_scope TEXT NOT NULL,
    connected_at TIMESTAMPTZ NOT NULL,
    collect_after TIMESTAMPTZ NOT NULL,
    last_synced_at TIMESTAMPTZ,
    window_after TIMESTAMPTZ,
    window_before TIMESTAMPTZ,
    page_token TEXT,
    state VARCHAR(30) NOT NULL,
    message VARCHAR(250) NOT NULL,
    retry_after TIMESTAMPTZ,
    failures INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE gmail_seen_messages (
    mailbox VARCHAR(254) NOT NULL,
    message_id VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(mailbox,message_id)
);
