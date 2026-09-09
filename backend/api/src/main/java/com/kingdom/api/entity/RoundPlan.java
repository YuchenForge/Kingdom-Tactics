package com.kingdom.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "round_plans")
public class RoundPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "round_id", nullable = false)
    private UUID roundId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "is_locked", nullable = false)
    private boolean locked = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "board_state", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> boardState = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lane_units", nullable = false, columnDefinition = "jsonb")
    private List<Object> laneUnits = new ArrayList<>(Arrays.asList(null, null, null, null, null));

    @Column(nullable = false)
    private int gold;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RoundPlan() {
    }

    public RoundPlan(UUID roundId, UUID playerId, int gold) {
        this.roundId = roundId;
        this.playerId = playerId;
        this.gold = gold;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRoundId() {
        return roundId;
    }

    public void setRoundId(UUID roundId) {
        this.roundId = roundId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public Map<String, Object> getBoardState() {
        return boardState;
    }

    public void setBoardState(Map<String, Object> boardState) {
        this.boardState = boardState;
    }

    public List<Object> getLaneUnits() {
        return laneUnits;
    }

    public void setLaneUnits(List<Object> laneUnits) {
        this.laneUnits = laneUnits;
    }

    public int getGold() {
        return gold;
    }

    public void setGold(int gold) {
        this.gold = gold;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
