CREATE TABLE game_events (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id       UUID         NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    round_number  INTEGER      NOT NULL,
    sequence_num  INTEGER      NOT NULL,
    event_type    VARCHAR(50)  NOT NULL,
    data          JSONB        NOT NULL,
    tick          INTEGER      NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),

    UNIQUE (game_id, round_number, sequence_num)
);

CREATE INDEX idx_game_events_game_round
    ON game_events(game_id, round_number);