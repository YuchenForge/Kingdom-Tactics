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
@Table(name = "commands")
public class Command {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "round_plan_id", nullable = false)
    private UUID roundPlanId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "command_type", nullable = false, length = 50)
    private String commandType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> parameters = new HashMap<>();

    @Column(name = "idempotency_key", nullable = false)
    private UUID idempotencyKey;

    @Column(name = "executed_at", nullable = false, updatable = false)
    private Instant executedAt;

    protected Command() {
    }

    public Command(
            UUID roundPlanId,
            int sequenceNumber,
            String commandType,
            Map<String, Object> parameters,
            UUID idempotencyKey
    ) {
        this.roundPlanId = roundPlanId;
        this.sequenceNumber = sequenceNumber;
        this.commandType = commandType;
        this.parameters = parameters != null ? parameters : new HashMap<>();
        this.idempotencyKey = idempotencyKey;
    }

    @PrePersist
    void onCreate() {
        if (executedAt == null) {
            executedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getRoundPlanId() {
        return roundPlanId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public String getCommandType() {
        return commandType;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }
}
