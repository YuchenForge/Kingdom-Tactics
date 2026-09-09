CREATE TABLE round_plans (
    id           UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    round_id     UUID      NOT NULL REFERENCES rounds(id) ON DELETE CASCADE,
    player_id    UUID      NOT NULL REFERENCES users(id),
    is_locked    BOOLEAN   NOT NULL DEFAULT FALSE,
    board_state  JSONB     NOT NULL,
    lane_units   JSONB     NOT NULL DEFAULT '[null,null,null,null,null]',
    gold         INTEGER   NOT NULL,
    locked_at    TIMESTAMP,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP NOT NULL DEFAULT now(),

    UNIQUE (round_id, player_id)
);

CREATE INDEX idx_round_plans_round_id ON round_plans(round_id);
CREATE INDEX idx_round_plans_player_id ON round_plans(player_id);