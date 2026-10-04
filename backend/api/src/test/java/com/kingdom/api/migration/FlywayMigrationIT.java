package com.kingdom.api.migration;

import com.kingdom.api.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliesAllMigrationsOnEmptyDatabase() {
        Integer migrationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class);
        assertThat(migrationCount).isEqualTo(12);

        assertThat(tableExists("users")).isTrue();
        assertThat(tableExists("games")).isTrue();
        assertThat(tableExists("game_players")).isTrue();
        assertThat(tableExists("rounds")).isTrue();
        assertThat(tableExists("round_plans")).isTrue();
        assertThat(tableExists("shop_offers")).isTrue();
        assertThat(tableExists("commands")).isTrue();
        assertThat(tableExists("game_events")).isTrue();
        assertThat(tableExists("game_state_snapshots")).isTrue();
    }

    @Test
    void phase2TablesHaveExpectedConstraints() {
        assertThat(uniqueConstraintExists("users", "users_username_key")).isTrue();
        assertThat(uniqueConstraintExists("users", "users_email_key")).isTrue();
        assertThat(uniqueConstraintExists("game_players", "game_players_game_id_player_id_key")).isTrue();
        assertThat(uniqueConstraintExists("game_players", "game_players_game_id_seat_key")).isTrue();
        assertThat(uniqueConstraintExists("rounds", "rounds_game_id_round_number_key")).isTrue();
        assertThat(uniqueConstraintExists("round_plans", "round_plans_round_id_player_id_key")).isTrue();

        assertThat(foreignKeyExists("games", "player_1_id", "users")).isTrue();
        assertThat(foreignKeyExists("games", "player_2_id", "users")).isTrue();
        assertThat(foreignKeyExists("game_players", "game_id", "games")).isTrue();
        assertThat(foreignKeyExists("rounds", "game_id", "games")).isTrue();
        assertThat(foreignKeyExists("round_plans", "round_id", "rounds")).isTrue();
    }

    @Test
    void phase3TablesHaveExpectedConstraintsAndColumns() {
        assertThat(uniqueConstraintExists("shop_offers", "shop_offers_round_id_player_id_slot_key")).isTrue();
        assertThat(uniqueConstraintExists("commands", "commands_round_plan_id_sequence_number_key")).isTrue();
        assertThat(uniqueConstraintExists("commands", "commands_round_plan_id_idempotency_key_key")).isTrue();

        assertThat(foreignKeyExists("shop_offers", "round_id", "rounds")).isTrue();
        assertThat(foreignKeyExists("shop_offers", "player_id", "users")).isTrue();
        assertThat(foreignKeyExists("commands", "round_plan_id", "round_plans")).isTrue();

        assertThat(columnExists("game_players", "keep_hp")).isTrue();
        assertThat(columnExists("round_plans", "version")).isTrue();
    }

    @Test
    void phase4CombatTablesHaveExpectedConstraintsAndColumns() {
        assertThat(uniqueConstraintExists("game_events", "game_events_game_id_round_number_sequence_num_key")).isTrue();
        assertThat(foreignKeyExists("game_events", "game_id", "games")).isTrue();

        assertThat(columnExists("game_events", "sequence_num")).isTrue();
        assertThat(columnExists("game_events", "event_type")).isTrue();
        assertThat(columnExists("game_events", "data")).isTrue();
        assertThat(columnExists("game_events", "tick")).isTrue();

        assertThat(uniqueConstraintExists(
                "game_state_snapshots",
                "game_state_snapshots_game_id_round_number_is_round_start_pl_key")).isTrue();
        assertThat(foreignKeyExists("game_state_snapshots", "game_id", "games")).isTrue();
        assertThat(foreignKeyExists("game_state_snapshots", "player_id", "users")).isTrue();

        assertThat(columnExists("game_state_snapshots", "is_round_start")).isTrue();
        assertThat(columnExists("game_state_snapshots", "keep_hp")).isTrue();
        assertThat(columnExists("game_state_snapshots", "board")).isTrue();
        assertThat(columnExists("game_state_snapshots", "lane")).isTrue();
        assertThat(columnExists("game_state_snapshots", "shop")).isTrue();

        assertThat(columnExists("rounds", "advanced_at")).isTrue();
        assertThat(indexExists("rounds", "idx_rounds_unadvanced")).isTrue();

        assertThat(columnExists("rounds", "outcome")).isTrue();
        assertThat(columnExists("rounds", "keep_damage")).isTrue();
    }

    @Test
    void phase6PresentationColumnsAndIndexExist() {
        assertThat(columnExists("rounds", "presentation_starts_at")).isTrue();
        assertThat(columnExists("rounds", "combat_ends_at")).isTrue();
        assertThat(columnExists("rounds", "presentation_ends_at")).isTrue();
        assertThat(columnExists("rounds", "tick_duration_ms")).isTrue();
        assertThat(indexExists("rounds", "idx_rounds_presentation_due")).isTrue();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """,
                Integer.class,
                tableName);
        return count != null && count == 1;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """,
                Integer.class,
                tableName,
                columnName);
        return count != null && count == 1;
    }

    private boolean uniqueConstraintExists(String tableName, String constraintName) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'UNIQUE'
                """,
                Integer.class,
                tableName,
                constraintName);
        return count != null && count == 1;
    }

    private boolean foreignKeyExists(String tableName, String columnName, String referencedTable) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.key_column_usage kcu
                JOIN information_schema.referential_constraints rc
                  ON kcu.constraint_name = rc.constraint_name
                 AND kcu.constraint_schema = rc.constraint_schema
                JOIN information_schema.key_column_usage ref
                  ON rc.unique_constraint_name = ref.constraint_name
                 AND rc.unique_constraint_schema = ref.constraint_schema
                WHERE kcu.table_schema = 'public'
                  AND kcu.table_name = ?
                  AND kcu.column_name = ?
                  AND ref.table_name = ?
                """,
                Integer.class,
                tableName,
                columnName,
                referencedTable);
        return count != null && count >= 1;
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = ?
                  AND indexname = ?
                """,
                Integer.class,
                tableName,
                indexName);
        return count != null && count == 1;
    }
}
