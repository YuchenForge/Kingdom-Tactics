package com.kingdom.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.kingdom.engine.cli.CombatResultDto;

class CombatCliRunnerTest {

    private static final Path SCENARIO1 = Path.of(
        "src/test/resources/combat/scenario1.json");

    @Test
    void run_prints_json_with_expected_scenario1_outcome() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = CombatCliRunner.run(
            new String[] {"--board-json=" + SCENARIO1},
            new PrintStream(out),
            new PrintStream(err));

        assertThat(exitCode).isZero();
        assertThat(err.toString()).isEmpty();

        CombatResultDto result = new Gson().fromJson(out.toString(), CombatResultDto.class);
        assertThat(result.seed).isEqualTo(12345L);
        assertThat(result.finalTick).isEqualTo(20);
        assertThat(result.endReason).isEqualTo("DRAW");
        assertThat(result.winnerPlayerId).isEqualTo(-1);
        assertThat(result.keepDamage).containsExactly(1, 1);
        assertThat(result.events).isNotEmpty();
        assertThat(result.survivingUnits).isEmpty();
    }

    @Test
    void run_is_deterministic_for_same_input() {
        String first = runAndCaptureStdout("--board-json=" + SCENARIO1);
        String second = runAndCaptureStdout("--board-json=" + SCENARIO1);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void run_seed_flag_overrides_json_seed() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = CombatCliRunner.run(
            new String[] {
                "--board-json=" + SCENARIO1,
                "--seed=99999"
            },
            new PrintStream(out),
            new PrintStream(new ByteArrayOutputStream()));

        assertThat(exitCode).isZero();

        CombatResultDto result = new Gson().fromJson(out.toString(), CombatResultDto.class);
        assertThat(result.seed).isEqualTo(99999L);
    }

    @Test
    void run_missing_board_json_exits_with_error() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = CombatCliRunner.run(
            new String[0],
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(err));

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("--board-json");
    }

    @Test
    void run_missing_file_exits_with_error() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = CombatCliRunner.run(
            new String[] {"--board-json=does-not-exist.json"},
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(err));

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("not found");
    }

    private static String runAndCaptureStdout(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = CombatCliRunner.run(
            args,
            new PrintStream(out),
            new PrintStream(new ByteArrayOutputStream()));
        assertThat(exitCode).isZero();
        return out.toString();
    }
}
