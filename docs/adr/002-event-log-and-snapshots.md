# ADR 002: Event Log and Snapshots

**Date:** 2024
**Status:** Accepted
**Affects:** Database schema, replay fidelity, rules versioning

---

## Context

Kingdom Tactics must support:
1. **Trustworthy replay:** A match can be replayed from stored data and produce identical results
2. **Auditability:** Every action taken during a match is recorded and can be inspected
3. **Rules versioning:** Rules can change over time without invalidating historical matches
4. **Bug reproduction:** A crash or unexpected result can be debugged by replaying the exact match

Two approaches were considered:

1. **State snapshots only:** Store board state after each round; recompute replay by re-running rules
2. **Event log + snapshots:** Store detailed event log per round; supplement with snapshots

---

## Decision

**Store both event logs and snapshots per round.**

Each round record includes:

```
round_id
round_number
rules_version
combat_seed

round_start_snapshot:
  - keep_hp[player_id]
  - gold[player_id]
  - board[player_id]
  - bench[player_id]
  - shop[player_id]

player_commands:
  - [COMMAND, COMMAND, ...]  # player 0
  - [COMMAND, COMMAND, ...]  # player 1

combat_events:
  - { type: "UNIT_MOVED", unit_id, x, y, tick }
  - { type: "ATTACK", attacker_id, target_id, damage, tick }
  - { type: "UNIT_DIED", unit_id, tick }
  - ... (detailed game events)

round_end_snapshot:
  - keep_hp[player_id]
  - surviving_units[player_id]
  - keep_damage_dealt
```

---

## Why both?

### Event log alone is insufficient

- Requires running combat engine to verify outcome (cannot validate without code)
- Engine bugs could cause replay to produce different results than original match
- Rules changes make it unclear which version to use for replay
- No fast path to check "did player X really take this much damage?"

### Snapshots alone are insufficient

- Cannot inspect what happened in detail (why did unit Y die?)
- Cannot replay step-by-step (only high-level round outcome)
- Cannot debug subtle bugs (off-by-one damage, wrong target selection)
- Cannot verify combat correctness without running engine

### Together, they provide:

**Snapshot = ground truth**, Event log = narrative detail

- Snapshots are immutable record of actual outcome
- Event log allows inspection and reproduction
- Mismatch between event-derived outcome and snapshot = bug alert
- Both are required to verify integrity: `replay_events(rules_v, seed, snapshot_start) == snapshot_end`

---

## Implementation

### Storage schema

```sql
game_state_snapshots
  snapshot_id  UUID PK
  game_id      UUID FK
  round_number INT
  is_start     BOOLEAN  -- true if round_start, false if round_end
  player_id    INT
  keep_hp      INT
  gold         INT
  board        JSONB    -- [unit_id or null] × 16
  bench        JSONB    -- [unit_id]
  shop         JSONB    -- [unit_definition]
  created_at   TIMESTAMP
  UNIQUE(game_id, round_number, is_start, player_id)

combat_events
  event_id     UUID PK
  game_id      UUID FK
  round_number INT
  sequence_num INT      -- 0-indexed event order
  event_type   VARCHAR  -- "UNIT_MOVED", "ATTACK", "UNIT_DIED", etc.
  data         JSONB    -- { unit_id, x, y, damage, ... }
  tick         INT      -- combat tick (0-indexed)
  created_at   TIMESTAMP
  UNIQUE(game_id, round_number, sequence_num)
```

### Replay algorithm

```pseudo
replay_round(game_id, round_number, rules_version) -> result
  snapshot_start = fetch_snapshot(game_id, round_number, is_start=true)
  commands = fetch_commands(game_id, round_number)
  seed = fetch_round_seed(game_id, round_number)

  engine = CombatEngine(rules_version, seed)
  result = engine.resolve(snapshot_start.board, commands)

  snapshot_end = fetch_snapshot(game_id, round_number, is_start=false)
  if result.board != snapshot_end.board
    raise ReplayMismatchError(...)  // bug in engine or original resolution
  
  return result
```

### Integrity checks

After every match, server validates:

```
for each round:
  events = fetch_combat_events(game_id, round)
  snapshot_end_stored = fetch_snapshot(..., is_start=false)
  
  // Recompute from event log
  snapshot_end_computed = rebuild_state_from_events(events, snapshot_start)
  
  if snapshot_end_computed != snapshot_end_stored:
    log(CRITICAL, "Round integrity check failed", game_id, round)
    alert(ops)
```

---

## Consequences

### Positive

1. **Deterministic replay:** Same rules version + seed + start state → identical events and outcome
2. **Auditability:** Every action and combat event is logged with timestamp and sequence
3. **Debugging:** Developers can re-run a match locally and step through events
4. **Rules versioning:** Can compare old vs new rules on same match data
5. **Fairness disputes:** Replay serves as immutable evidence
6. **Performance optimization:** Snapshots provide quick "where were we?" without replaying

### Negative

1. **Storage:** Every round stores full board state (16 cells × 2 boards) + full event log
   - Estimate: ~1 KB per round × 8 rounds × millions of matches = terabytes
   - **Mitigation:** Compress JSON, archive old matches, prune events after verification window

2. **Complexity:** Must maintain event schema and compatibility across rule versions
   - **Mitigation:** Schema versioning; event is immutable once written

3. **Consistency burden:** Snapshots and events must be kept in sync
   - **Mitigation:** Atomic writes; post-match integrity check

---

## Alternatives considered

### 1. Snapshots only (rejected)

**Approach:**
- Store only board state at round start and end
- Replay by running engine from start state

**Why rejected:**
- Cannot inspect detailed combat (why did unit A attack unit B?)
- Cannot debug without running code
- Engine bugs would invalidate replays
- No fast path to answer questions like "total damage taken this round?"

### 2. Event log only (rejected)

**Approach:**
- Store only combat events
- Replay by re-running engine

**Why rejected:**
- Requires running engine to verify (cannot audit without code)
- Snapshot mismatch is undetectable
- No way to check if events are correct except by running them
- Storage not smaller (full event log ≈ full snapshot)

### 3. Compressed replay (state diffs only)

**Approach:**
- Store only deltas from previous round state

**Why rejected:**
- More complex to reconstruct state at arbitrary round
- Only saves storage if matches have many rounds (8 is small)
- Adds complexity without clear benefit

---

## Related decisions

- **ADR 001:** Server-authoritative state (ensures snapshots are ground truth)
- **ADR 003:** Deterministic combat (ensures replay is exact)

---

## Questions and answers

**Q: What if the event log is huge for a 40-second combat?**
A: At 250 ms ticks, max 160 ticks per combat. Average ~8 units per board, ~2–4 events per tick. Estimate: 2000–5000 events per combat. ~100 KB per round in JSON (gzipped: ~20 KB).

**Q: How do we handle rules changes that affect event format?**
A: Store `rules_version` with each round. Use a rules registry that knows how to deserialize events for each version. Always replay with the version that created the event.

**Q: What if we want to "fix" a match result (e.g., bug discovered)?**
A: Do not mutate snapshots. Instead, flag the match as having an integrity issue, replay with fixed rules to verify the correct outcome, and award rating corrected retroactively. Keep original data immutable.

**Q: Can replays be shared/exported?**
A: Yes. A replay is: `game_id + snapshots + events` from the match. Can be JSON-exported for sharing/analysis, or displayed in UI by fetching from server.
