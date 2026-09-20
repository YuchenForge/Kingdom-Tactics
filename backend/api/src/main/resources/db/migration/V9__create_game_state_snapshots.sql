CREATE TABLE game_state_snapshots (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id         UUID         NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    round_number    INTEGER      NOT NULL,
    is_round_start  BOOLEAN      NOT NULL,
    player_id       UUID         NOT NULL REFERENCES users(id),
    keep_hp         INTEGER      NOT NULL,
    gold            INTEGER      NOT NULL,
    board           JSONB        NOT NULL,
    lane            JSONB        NOT NULL DEFAULT '[]',
    shop            JSONB,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),

    UNIQUE (game_id, round_number, is_round_start, player_id)
);

CREATE INDEX idx_game_state_snapshots_game_round
    ON game_state_snapshots(game_id, round_number);