package com.kingdom.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rounds")
public class Round {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    @Column(nullable = false, length = 50)
    private String state;

    @Column(name = "rules_version", nullable = false, length = 20)
    private String rulesVersion = "1.0";

    @Column(name = "combat_seed")
    private Long combatSeed; // null until Phase 4

    @Column(name = "planning_deadline", nullable = false)
    private Instant planningDeadline;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected Round() {
    }

    public Round(UUID gameId, int roundNumber, String state, Instant planningDeadline) {
        this.gameId = gameId;
        this.roundNumber = roundNumber;
        this.state = state;
        this.planningDeadline = planningDeadline;
    }

    @PrePersist
    void onCreate() {
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getGameId() {
        return gameId;
    }

    public void setGameId(UUID gameId) {
        this.gameId = gameId;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public void setRoundNumber(int roundNumber) {
        this.roundNumber = roundNumber;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getRulesVersion() {
        return rulesVersion;
    }

    public void setRulesVersion(String rulesVersion) {
        this.rulesVersion = rulesVersion;
    }

    public Long getCombatSeed() {
        return combatSeed;
    }

    public void setCombatSeed(Long combatSeed) {
        this.combatSeed = combatSeed;
    }

    public Instant getPlanningDeadline() {
        return planningDeadline;
    }

    public void setPlanningDeadline(Instant planningDeadline) {
        this.planningDeadline = planningDeadline;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
