# Game and round state machine

The server owns every phase transition. A match contains at most eight rounds; each preparation lasts 45 seconds unless both players lock earlier.

```text
WAITING_FOR_PLAYERS
        │ second player joins
        ▼
PREPARATION ◄──────────────────────────────┐
        │ both locked / deadline          │ next round
        ▼                                 │
      LOCKED                              │
        │ TX 1: claim and persist seed    │
        ▼                                 │
    RESOLVING                             │
        │ simulate outside transaction    │
        │ TX 2: commit result + schedule  │
        ▼                                 │
   ROUND_RESULT                           │
        │ wait until presentation ends    │
        │ TX 3: advance once ─────────────┘
        ▼ match over
     FINISHED
```

`ROUND_RESULT` is both a durable result checkpoint and the combat presentation period. Combat has already been computed when clients animate it. Past rounds remain `ROUND_RESULT` after advancement; `games.state` and `games.current_round` identify the active phase.

## Transitions

| Transition | Trigger and effects |
|---|---|
| Waiting → preparation | Second player joins. Create round 1, two plans with 10 gold, shops, and a deadline 45 seconds ahead. |
| Preparation → locked | Both plans lock manually, or the server deadline expires and locks any remaining plan. Empty formations are valid. |
| Locked → resolving | TX 1 claims the round with `FOR UPDATE SKIP LOCKED`, persists `combat_seed`, and commits before simulation. |
| Resolving → round result | TX 2 requires the round still be `RESOLVING` under a row lock. Persist events, end snapshots, outcome, Keep damage, and presentation schedule atomically. |
| Round result → preparation | Once presentation is due, TX 3 copies locked formations into new plans, adds 5 gold, creates new shops, and starts a fresh 45-second deadline. Set `advanced_at` on the completed round. |
| Round result → finished | Once presentation is due and the match has ended, TX 3 records the winner or draw, applies ratings, sets `finished_at` and `advanced_at`. |

Keep HP is updated only in TX 2 and clamped to zero. A normal round damages the loser's Keep by `1 + survivor_count + floor(surviving_hp / 10)`. A mutual wipe damages each Keep by 1. Plans are never overwritten with combat survivors.

## Presentation deadline

TX 2 persists these fields together:

| Field | Meaning |
|---|---|
| `presentation_starts_at` | Resolution time + 3 seconds; tick 0 of playback |
| `tick_duration_ms` | 250 ms per logical tick |
| `combat_ends_at` | Start + final logical tick × tick duration |
| `presentation_ends_at` | Combat end + 1 second for final effects + 3 seconds for results |

Both continuation and match finish wait until `presentation_ends_at`. Legacy rows with all four fields null may advance immediately. Clients never acknowledge or extend the deadline. If the worker is late, the client shows the completed result and continues polling.

State polls expose the shared schedule as `combatPresentation` only in `ROUND_RESULT`; it is null in other phases and for legacy rows. `serverTime` anchors client presentation. Reconnecting clients join the elapsed timeline and can retrieve historical results via `latestResolvedRound` and the round-result endpoint.

## Match-end decision

TX 3 evaluates these rules in order, after damage and after presentation:

1. Both Keeps are zero: draw.
2. Exactly one Keep is zero: the other player wins.
3. Round 8 is complete: higher Keep HP wins, then higher remaining gold, then draw.
4. Otherwise: start the next round.

A win adds 25 rating points to the winner and subtracts 25 from the loser. Draws leave ratings unchanged. This is a fixed rating adjustment, not Elo. A finished draw has `winner_id = NULL`.

## Visibility and command behavior

During preparation, each player can see their own board, lane, and shop. Opponent Keep HP, lock status, and total owned unit count are exposed. After preparation closes, `combatUnits` reveals both deployed formations in `LOCKED`, `RESOLVING`, and `ROUND_RESULT`; opponent shop and unused lane units stay private.

Commands require participant authorization, the current round, and an idempotency key. Retrying a committed command in the supported preparation/locked phase returns the current plan snapshot. Late retries after resolution or advancement return `409 WRONG_GAME_STATE`. The client must refetch and must not silently repeat the action in the next round.

Optimistic plan versions reject conflicting updates. Manual locking and deadline finalization use database locks to coordinate transitions. Locked-plan edits return `423`; invalid commands use the specific statuses in the [API contract](api-contract.md).

## Recovery and scheduling

The worker scans every 1 second by default, with three recovery paths:

| Candidate | Work |
|---|---|
| `LOCKED` | Claim, simulate, commit; attempt advancement when due |
| `RESOLVING` | Recompute from the same seed and locked plans; commit only if still resolving |
| Unadvanced `ROUND_RESULT` with presentation due | Advance only; never repeat damage or events |

TX 2 uses the `RESOLVING` state lock as its commit guard. TX 3 uses the result state, presentation deadline, and `advanced_at`. Concurrent simulation is allowed; durable effects are committed once. No database lock spans combat simulation or browser playback.

The planning deadline monitor sweeps every 10 seconds as a safety net. API reads and commands also perform opportunistic deadline checks. Disconnected players therefore do not block the match. Repeated job failures are logged; there is no automatic alerting integration.

## Example timeline

For a round whose final logical tick is 20:

| Time | State | Event |
|---|---|---|
| 12:00:00 | `ROUND_RESULT` | TX 2 commits events, damage, and timing |
| 12:00:03 | `ROUND_RESULT` | Combat playback starts |
| 12:00:08 | `ROUND_RESULT` | Final logical tick |
| 12:00:09 | `ROUND_RESULT` | Final effects settle; result feedback |
| 12:00:12 or later | `PREPARATION` or `FINISHED` | Worker advances once |

A new preparation deadline is 45 seconds after actual advancement. Polling delay or a disconnected browser does not shorten it.

## Operator queries

```sql
-- Expired preparations awaiting finalization.
SELECT id, game_id, round_number, planning_deadline
FROM rounds
WHERE state = 'PREPARATION' AND planning_deadline <= now();

-- Resolving rounds: started_at is round creation, not a worker lease.
SELECT id, game_id, round_number, combat_seed, started_at
FROM rounds
WHERE state = 'RESOLVING';

-- Results eligible for advancement, excluding active presentation windows.
SELECT id, game_id, round_number, presentation_ends_at
FROM rounds
WHERE state = 'ROUND_RESULT' AND advanced_at IS NULL
  AND (presentation_ends_at IS NULL OR presentation_ends_at <= now());
```

Health probes are described in [architecture](architecture.md#security-and-operations). There is no game-count health endpoint or automatic cleanup of abandoned waiting games.
