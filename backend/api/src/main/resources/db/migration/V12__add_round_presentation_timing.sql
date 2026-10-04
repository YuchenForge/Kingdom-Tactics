-- Additive migration: legacy results remain NULL and may advance immediately.
-- New TX2 commits always persist the complete schedule in their result transaction.
ALTER TABLE rounds
    ADD COLUMN presentation_starts_at TIMESTAMP,
    ADD COLUMN combat_ends_at TIMESTAMP,
    ADD COLUMN presentation_ends_at TIMESTAMP,
    ADD COLUMN tick_duration_ms INTEGER,
    ADD CONSTRAINT chk_round_presentation_timing CHECK (
        (presentation_starts_at IS NULL AND combat_ends_at IS NULL
            AND presentation_ends_at IS NULL AND tick_duration_ms IS NULL)
        OR
        (presentation_starts_at IS NOT NULL AND combat_ends_at IS NOT NULL
            AND presentation_ends_at IS NOT NULL AND tick_duration_ms IS NOT NULL
            AND tick_duration_ms > 0
            AND combat_ends_at >= presentation_starts_at
            AND presentation_ends_at >= combat_ends_at)
    );

CREATE INDEX idx_rounds_presentation_due
    ON rounds(presentation_ends_at)
    WHERE state = 'ROUND_RESULT' AND advanced_at IS NULL;
