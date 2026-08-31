CREATE TABLE game_players (
    id         UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id    UUID      NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    player_id  UUID      NOT NULL REFERENCES users(id),
    seat       INTEGER   NOT NULL,   -- 0 or 1
    is_ready   BOOLEAN   NOT NULL DEFAULT FALSE,
    joined_at  TIMESTAMP NOT NULL DEFAULT now(),

    UNIQUE (game_id, player_id),
    UNIQUE (game_id, seat)
);

CREATE INDEX idx_game_players_game_id ON game_players(game_id);
CREATE INDEX idx_game_players_player_id ON game_players(player_id);