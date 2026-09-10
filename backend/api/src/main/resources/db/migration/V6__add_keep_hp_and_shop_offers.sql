-- Keep HP lives on membership; Phase 3 reads it, Phase 4 mutates after combat.
ALTER TABLE game_players
    ADD COLUMN keep_hp INTEGER NOT NULL DEFAULT 20;

-- Per-player, per-round shop slots (0–2). NULL unit_type = sold/empty.
CREATE TABLE shop_offers (
    id         UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    round_id   UUID      NOT NULL REFERENCES rounds(id) ON DELETE CASCADE,
    player_id  UUID      NOT NULL REFERENCES users(id),
    slot       INTEGER   NOT NULL,
    unit_type  VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT now(),

    UNIQUE (round_id, player_id, slot)
);

CREATE INDEX idx_shop_offers_round_id ON shop_offers(round_id);
