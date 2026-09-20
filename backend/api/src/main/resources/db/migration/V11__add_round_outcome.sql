ALTER TABLE rounds
    ADD COLUMN outcome     VARCHAR(30),
    ADD COLUMN keep_damage JSONB;