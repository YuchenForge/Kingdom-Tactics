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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "game_events")
public class GameEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "round_number", nullable = false)
    private int roundNumber;

    @Column(name = "sequence_num", nullable = false)
    private int sequenceNum;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> data = new HashMap<>();

    @Column(nullable = false)
    private int tick;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GameEvent() {
    }

    public GameEvent(
            UUID gameId,
            int roundNumber,
            int sequenceNum,
            String eventType,
            Map<String, Object> data,
            int tick
    ) {
        this.gameId = gameId;
        this.roundNumber = roundNumber;
        this.sequenceNum = sequenceNum;
        this.eventType = eventType;
        this.data = data != null ? data : new HashMap<>();
        this.tick = tick;
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

    public int getSequenceNum() {
        return sequenceNum;
    }

    public String getEventType() {
        return eventType;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public int getTick() {
        return tick;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
