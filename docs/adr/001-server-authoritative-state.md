# ADR 001: Server-Authoritative State

**Date:** 2024
**Status:** Accepted
**Affects:** Backend architecture, client trust model, security

---

## Context

Kingdom Tactics is a competitive 1v1 auto-battler where players spend real-world time building teams and making tactical decisions. The game outcome must be trustworthy: players should have confidence that the opponent cannot cheat to gain an unfair advantage.

We considered two state models:

1. **Client-authoritative:** Clients send commands; both clients compute shared game state locally and sync
2. **Server-authoritative:** Clients send commands; server computes and persists authoritative state; clients receive only what they're allowed to see

---

## Decision

**Adopt server-authoritative state.**

The server is the single source of truth for:
- Current player gold, Keep HP, unit positions, and board state
- Combat outcomes and Keep damage
- Win conditions and match resolution
- Game eligibility and authorization

Clients may cache state locally for UI responsiveness, but must reconcile against server on each update.

---

## Implementation

### State flow

```
CLIENT_1           SERVER              CLIENT_2
   │                 │                   │
   ├─ BUY_UNIT ─────>│                   │
   │                 ├─ validate (gold)  │
   │                 ├─ persist          │
   │                 ├─ broadcast update>├─ (visible: unit count only, not price)
   │                 │                   │
   │                 │<───── SELL_UNIT ──┤
   │                 ├─ validate         │
   │                 ├─ persist          │
   │<─ broadcast ────┤                   │
   │                 │                   │
```

### What clients see

**Public information:**
- Own gold, Keep HP, board layout, bench count
- Opponent's Keep HP, board layout visible during combat
- Locked plan summary (# of units placed, but not positions or types until combat)
- Combat events (damage dealt, unit movements, deaths)
- Match result and replay

**Hidden information (until round resolves):**
- Opponent's unconfirmed shop offers
- Opponent's planned unit positions before lock
- Opponent's pending commands

---

## Consequences

### Positive

1. **Trust and fairness:** No possibility of client-side cheating (no Inspect Element gold injection)
2. **Simplicity:** Single source of truth reduces sync bugs and race conditions
3. **Verifiability:** All outcomes are reproducible from stored events (audit trail)
4. **Flexibility:** Server can enforce new rules without client changes (soft rule updates)
5. **Observability:** Server logs every action; easier to debug and investigate disputes

### Negative

1. **Network latency:** Client action → server validation → client update creates perceived lag
   - **Mitigation:** Optimistic local updates on client; reconcile on server response
2. **Server load:** Server handles all game state transitions and validation
   - **Mitigation:** Horizontal scaling via state sharding by game_id
3. **Complexity:** Requires careful authorization checks (ensure players only see their own data)
   - **Mitigation:** Declarative data filters; write query tests

### Operational

1. **Backend downtime:** If server goes down, players cannot play
   - **Mitigation:** High availability, multi-region deployment
2. **Client must be online:** No offline play (but acceptable for this genre)
3. **Replay depends on server:** Replays stored server-side, not self-contained in client
   - **Mitigation:** Store entire round snapshot and events for perfect reconstruction

---

## Alternatives considered

### 1. Client-authoritative (rejected)

**How it would work:**
- Both clients compute shared state independently
- Clients exchange commands and validate locally
- Clients sync state periodically

**Why rejected:**
- Vulnerable to cheating (client could send fraudulent commands)
- Race conditions on simultaneous actions
- Impossible to resolve dispute (no trusted record)
- Network latency creates desync and inconsistency

### 2. Hybrid (client + server validation)

**How it would work:**
- Client computes state optimistically
- Server validates and corrects
- Client reconciles on mismatch

**Why rejected:**
- Complexity without clear benefit
- Still requires server to be source of truth
- Client validation code is security-irrelevant (server still decides)
- Harder to debug discrepancies

---

## Related decisions

- **ADR 002:** Event log and snapshots (enables verification of server-authoritative state)
- **ADR 003:** Deterministic combat (ensures replays match server outcomes)

---

## Questions and answers

**Q: Can a client cheat by sending invalid commands (e.g., BUY without gold)?**
A: No. Server validates all commands before applying. Invalid commands are rejected with a 400 error.

**Q: What if the client and server disagree on state?**
A: Server is always right. Client should fetch fresh state and reconcile.

**Q: How do we prevent Man-in-the-Middle attacks on state updates?**
A: Use HTTPS only. State is confidential (opponent's shop, plan) so encrypt over TLS.

**Q: Can we eventually add P2P if needed?**
A: No. P2P requires client-side trust, which breaks fairness. Stick with server-authoritative.
