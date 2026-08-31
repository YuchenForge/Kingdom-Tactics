package com.kingdom.api.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class FlywayMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("kingdom_tactics_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliesAllMigrationsOnEmptyDatabase() {
        Integer migrationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class);
        assertThat(migrationCount).isEqualTo(5);

        assertThat(tableExists("users")).isTrue();
        assertThat(tableExists("games")).isTrue();
        assertThat(tableExists("game_players")).isTrue();
        assertThat(tableExists("rounds")).isTrue();
        assertThat(tableExists("round_plans")).isTrue();
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
}
