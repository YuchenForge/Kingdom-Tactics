ALTER TABLE rounds
    ADD COLUMN advanced_at TIMESTAMP;

CREATE INDEX idx_rounds_unadvanced
    ON rounds(state, advanced_at);
