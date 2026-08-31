-- player_2_id is nullable until the second player joins (WAITING_FOR_PLAYERS).
-- UNIQUE(player_1_id, player_2_id, started_at) omitted: NULL player_2_id breaks that constraint.

CREATE TABLE games (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    player_1_id     UUID         NOT NULL REFERENCES users (id),
    player_2_id     UUID         REFERENCES users (id),
    state           VARCHAR(50)  NOT NULL,
    current_round   INTEGER      NOT NULL DEFAULT 0,
    winner_id       UUID         REFERENCES users (id),
    started_at      TIMESTAMP    NOT NULL DEFAULT now(),
    finished_at     TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),

    CHECK (player_2_id IS NULL OR player_1_id <> player_2_id)
);

CREATE INDEX idx_games_state ON games (state);
CREATE INDEX idx_games_winner_id ON games (winner_id);
CREATE INDEX idx_games_player_1_id ON games (player_1_id);
CREATE INDEX idx_games_player_2_id ON games (player_2_id);
