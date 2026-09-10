CREATE TABLE commands (
    id              UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    round_plan_id   UUID      NOT NULL REFERENCES round_plans(id) ON DELETE CASCADE,
    sequence_number INTEGER   NOT NULL,
    command_type    VARCHAR(50) NOT NULL,
    -- BUY_UNIT, SELL_UNIT, PLACE_UNIT, MOVE_UNIT, REFRESH_SHOP, LOCK_BOARD
    parameters      JSONB     NOT NULL,
    idempotency_key UUID      NOT NULL,
    executed_at     TIMESTAMP NOT NULL DEFAULT now(),

    UNIQUE (round_plan_id, sequence_number),
    UNIQUE (round_plan_id, idempotency_key)
);

CREATE INDEX idx_commands_round_plan_id ON commands(round_plan_id);

-- Needed for JPA @Version on RoundPlan (concurrent command races).
ALTER TABLE round_plans
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
