CREATE TABLE rounds (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id             UUID         NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    round_number        INTEGER      NOT NULL,
    state               VARCHAR(50)  NOT NULL,
    rules_version       VARCHAR(20)  NOT NULL DEFAULT '1.0',
    combat_seed         BIGINT,
    planning_deadline   TIMESTAMP    NOT NULL,
    started_at          TIMESTAMP    NOT NULL DEFAULT now(),
    finished_at         TIMESTAMP,

    UNIQUE (game_id, round_number),
    CHECK (round_number >= 1 AND round_number <= 8)
);

CREATE INDEX idx_rounds_game_id ON rounds(game_id);
CREATE INDEX idx_rounds_state ON rounds(state);
CREATE INDEX idx_rounds_started_at ON rounds(started_at);