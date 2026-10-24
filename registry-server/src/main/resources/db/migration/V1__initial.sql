CREATE TABLE challenges (
    challenge_id TEXT PRIMARY KEY,
    nonce TEXT NOT NULL,
    listing_type TEXT NOT NULL,
    action TEXT NOT NULL,
    audience TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    consumed_at INTEGER
);

CREATE INDEX challenges_expiry_idx ON challenges(expires_at);

CREATE TABLE attestation_receipts (
    receipt_hash BLOB PRIMARY KEY,
    owner_ref TEXT NOT NULL,
    listing_type TEXT NOT NULL,
    action TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    consumed_at INTEGER
);

CREATE INDEX attestation_receipts_expiry_idx ON attestation_receipts(expires_at);

CREATE TABLE owner_denylist (
    owner_ref TEXT PRIMARY KEY,
    reason_code TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE publications (
    listing_id TEXT PRIMARY KEY,
    revision INTEGER NOT NULL CHECK (revision >= 1),
    listing_type TEXT NOT NULL,
    entry_json BLOB NOT NULL,
    target_ref TEXT NOT NULL,
    owner_ref TEXT NOT NULL,
    lease_id TEXT NOT NULL UNIQUE,
    lease_proof_hash BLOB NOT NULL,
    last_heartbeat INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE(owner_ref, listing_type)
);

CREATE INDEX publications_catalog_idx ON publications(expires_at, listing_id);
CREATE INDEX publications_type_catalog_idx ON publications(listing_type, expires_at, listing_id);

CREATE TABLE join_handles (
    handle_hash BLOB PRIMARY KEY,
    listing_id TEXT NOT NULL REFERENCES publications(listing_id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL,
    consumed_at INTEGER
);

CREATE INDEX join_handles_expiry_idx ON join_handles(expires_at);

CREATE TABLE reports (
    report_id TEXT PRIMARY KEY,
    listing_id TEXT NOT NULL,
    owner_ref TEXT NOT NULL,
    reason TEXT NOT NULL,
    reporter_hash BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN',
    UNIQUE(listing_id, reason, reporter_hash)
);

CREATE INDEX reports_queue_idx ON reports(status, created_at);

CREATE TABLE moderation_audit (
    audit_id TEXT PRIMARY KEY,
    listing_id TEXT NOT NULL,
    owner_ref TEXT NOT NULL,
    action TEXT NOT NULL,
    reason_code TEXT NOT NULL,
    actor_ref TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX moderation_audit_time_idx ON moderation_audit(created_at);
