package com.kingdom.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "game_state_snapshots")
public class GameStateSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    @Column(name = "is_round_start", nullable = false)
    private boolean roundStart;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "keep_hp", nullable = false)
    private int keepHp;

    @Column(nullable = false)
    private int gold;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Object> board = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Object> lane = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Object> shop;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GameStateSnapshot() {
    }

    public GameStateSnapshot(
            UUID gameId,
            int roundNumber,
            boolean roundStart,
            UUID playerId,
            int keepHp,
            int gold,
            List<Object> board,
            List<Object> lane,
            List<Object> shop
    ) {
        this.gameId = gameId;
        this.roundNumber = roundNumber;
        this.roundStart = roundStart;
        this.playerId = playerId;
        this.keepHp = keepHp;
        this.gold = gold;
        this.board = board != null ? board : new ArrayList<>();
        this.lane = lane != null ? lane : new ArrayList<>();
        this.shop = shop;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getGameId() {
        return gameId;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public boolean isRoundStart() {
        return roundStart;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public int getKeepHp() {
        return keepHp;
    }

    public void setKeepHp(int keepHp) {
        this.keepHp = keepHp;
    }

    public int getGold() {
        return gold;
    }

    public List<Object> getBoard() {
        return board;
    }

    public void setBoard(List<Object> board) {
        this.board = board;
    }

    public List<Object> getLane() {
        return lane;
    }

    public void setLane(List<Object> lane) {
        this.lane = lane;
    }

    public List<Object> getShop() {
        return shop;
    }

    public void setShop(List<Object> shop) {
        this.shop = shop;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
