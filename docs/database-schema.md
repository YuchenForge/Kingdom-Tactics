# Database schema

PostgreSQL stores accounts, matches, planning commands, and immutable combat results. Flyway migrations V1–V12 in [`backend/api/src/main/resources/db/migration/`](../backend/api/src/main/resources/db/migration/) are the executable schema. The API applies them at startup; JPA validates the resulting schema.

## Relationships

```text
users ──< game_players >── games ──< rounds ──< round_plans ──< commands
  │                          │         └──────< shop_offers
  │                          ├───────────────< game_events
  │                          └───────────────< game_state_snapshots
  └── referenced by game seats, winner, plans, offers, and snapshots
```

`game_events` and `game_state_snapshots` reference `games` and carry a round number; they do not have a foreign key to `rounds`. Plans reference users directly, rather than membership rows. There is no `ratings` history table: the current rating is stored on `users`.

## Tables and constraints

| Table | Data | Key constraints |
|---|---|---|
| `users` | UUID, username, email, BCrypt hash (`BYTEA`), rating (default 1200), timestamps | Unique username and email |
| `games` | Player user IDs, state, current round, winner, timestamps | Player 2 may be null while waiting; players must differ |
| `game_players` | Game/user membership, seat, Keep HP (default 20), join time | Unique `(game_id, player_id)` and `(game_id, seat)` |
| `rounds` | Game, round number, state, rules version, seed, deadlines, result, advancement marker | Unique `(game_id, round_number)`; round number 1–8 |
| `round_plans` | Round/user, lock state, JSON board/lane, gold, optimistic version, timestamps | Unique `(round_id, player_id)` |
| `shop_offers` | Round/user, slot, nullable unit type | Unique `(round_id, player_id, slot)` |
| `commands` | Plan, sequence, command type/parameters, idempotency UUID, execution time | Unique plan/sequence and plan/idempotency key |
| `game_events` | Game/round, event sequence/type/data/tick | Unique `(game_id, round_number, sequence_num)` |
| `game_state_snapshots` | Game/round/user, start flag, Keep HP, gold, board/lane/shop JSON | Unique `(game_id, round_number, is_round_start, player_id)` |

IDs default to `gen_random_uuid()`. Timestamp columns use PostgreSQL `TIMESTAMP`; API timestamps are serialized as ISO-8601 instants. The application restricts seats to 0/1, shop slots to 0–2, and lane slots to 0–4; these bounds are not all database CHECK constraints.

`games.current_round` is 0 while waiting and 1–8 after joining. `winner_id` is null while in progress and for a finished draw. `started_at` on a game records creation, not the start of round 1. Multiple games between the same players are allowed.

## Round lifecycle fields

| Field | Written by / meaning |
|---|---|
| `rules_version` | Round creation; currently `1.0` |
| `combat_seed` | TX 1 claim; reused on simulation retries |
| `outcome` | TX 2; `PLAYER_VICTORY`, `ENEMY_VICTORY`, `TIME_LIMIT`, or `DRAW` |
| `keep_damage` | TX 2; JSON object keyed by seat, e.g. `{"0":0,"1":3}` |
| `finished_at` | TX 2 resolution time |
| `presentation_starts_at`, `combat_ends_at`, `presentation_ends_at`, `tick_duration_ms` | TX 2 shared playback schedule |
| `advanced_at` | TX 3; set once when starting the next round or finishing the match |

V12 requires presentation fields to be either all null or all set, with positive tick duration and ordered timestamps. Legacy rows with all-null timing may advance immediately. Other results wait until `presentation_ends_at`.

Historical round state remains `ROUND_RESULT`. Current game phase lives on `games`. Keep HP is updated on `game_players` in TX 2 and clamped to zero; historical HP comes from snapshots, not live membership rows.

## JSON representations

### Locked planning board and lane

`round_plans.board_state` is a sparse map in local placement coordinates:

```json
{
  "0,2": { "id": "unit_001", "type": "Knight", "level": 2 },
  "1,2": { "id": "unit_002", "type": "Squire", "level": 1 }
}
```

`lane_units` is a five-slot array; the database default is five nulls:

```json
[
  { "id": "unit_003", "type": "Ranger", "level": 1 },
  null, null, null, null
]
```

Locked plans are immutable combat inputs. The next round gets a new copy of the locked formation, including units that died during combat. Combat resets their HP when boards merge.

### Commands

Planning command sequences are **0-based** within each plan. Parameters match the operation; relocation retains the nested destination:

```json
{
  "unitId": "unit_001",
  "to": { "type": "BOARD", "x": 0, "y": 1 }
}
```

An idempotency UUID is scoped to the plan. The unique constraint prevents a duplicate effect; supported retries return the current committed snapshot rather than a cached original response.

### Combat events and snapshots

Combat event sequences are **1-based** and restart each round. Each row has a type, tick, and `data` JSON. `UNIT_PLACED` includes unit ID/type, seat (`playerId`), global coordinates, level, current HP, and maximum HP. Recorded display stats allow playback without looking up today's balance table. See [event payloads](api-contract.md#event-streaming-resolving--after-resolution).

Combat-end snapshots use `is_round_start = false`. Their `board` field is a survivor array in global combat coordinates:

```json
[
  { "id": "unit_001", "type": "Knight", "level": 2,
    "x": 1, "y": 3, "currentHp": 10, "maxHp": 27 }
]
```

Snapshot `keep_hp` and `gold` preserve the resolved round's values. End-snapshot lane/shop fields are not the opponent's private planning data. The API returns survivors and Keep HP; these snapshots never replace the locked formation.

## Indexes

In addition to primary keys and unique-constraint indexes, migrations create indexes for:

- Game state, winner, and each player column.
- Membership game/user lookup and plan round/user lookup.
- Round game, state, start time, and `(state, advanced_at)`.
- Shop round lookup and command plan lookup.
- Event and snapshot `(game_id, round_number)` lookup.
- Due presentation time, restricted to unadvanced `ROUND_RESULT` rows.

There are no standalone event-type or game-created-time indexes in the current migrations. Add indexes in response to measured query needs.

## Migrations

| Version | Migration |
|---|---|
| V1 | [create users](../backend/api/src/main/resources/db/migration/V1__create_users.sql) |
| V2 | [create games](../backend/api/src/main/resources/db/migration/V2__create_games.sql) |
| V3 | [create gam players](../backend/api/src/main/resources/db/migration/V3__create_gam_players.sql) |
| V4 | [create rounds](../backend/api/src/main/resources/db/migration/V4__create_rounds.sql) |
| V5 | [create round plans](../backend/api/src/main/resources/db/migration/V5__create_round_plans.sql) |
| V6 | [add keep hp and shop offers](../backend/api/src/main/resources/db/migration/V6__add_keep_hp_and_shop_offers.sql) |
| V7 | [create commands](../backend/api/src/main/resources/db/migration/V7__create_commands.sql) |
| V8 | [create game events](../backend/api/src/main/resources/db/migration/V8__create_game_events.sql) |
| V9 | [create game state snapshots](../backend/api/src/main/resources/db/migration/V9__create_game_state_snapshots.sql) |
| V10 | [add rounds advanced at](../backend/api/src/main/resources/db/migration/V10__add_rounds_advanced_at.sql) |
| V11 | [add round outcome](../backend/api/src/main/resources/db/migration/V11__add_round_outcome.sql) |
| V12 | [add round presentation timing](../backend/api/src/main/resources/db/migration/V12__add_round_presentation_timing.sql) |

Flyway records applied versions and checksums in `flyway_schema_history`. Do not edit already-applied migrations; use additive migrations. Production disables clean and baseline-on-migrate. A code rollback does not undo a schema migration.

## Local backup and verification

From the repository root, with the default local Compose database running:

```bash
docker compose exec -T postgres pg_dump -U kingdom_user kingdom_tactics > backup.sql
```

Restore only into an empty, disposable database prepared for that purpose. Production backups and point-in-time recovery depend on the database provider and its retained backups/WAL; a restart command alone cannot restore a historical state.

`FlywayMigrationIT` checks migrations against PostgreSQL through Testcontainers. Backend integration tests also validate JPA mappings and transactional behavior. See [test commands](../README.md#tests) and [deployment operations](../deployment/README.md).
