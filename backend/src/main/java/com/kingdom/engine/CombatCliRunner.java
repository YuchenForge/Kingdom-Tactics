package com.kingdom.engine;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.kingdom.engine.cli.BoardBuilder;
import com.kingdom.engine.cli.CombatResultDto;
import com.kingdom.engine.cli.CombatResultMapper;
import com.kingdom.engine.cli.PlayerDto;
import com.kingdom.engine.cli.ReplayFileDto;
import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.engine.service.CombatEngine;

/**
 * CLI entry point: reads a board JSON fixture, runs combat, prints JSON to stdout.
 *
 * <p>Usage:
 * <pre>
 *   java -jar kingdom-tactics-0.1.0-jar-with-dependencies.jar \
 *     --board-json=path/to/board.json [--seed=NNN]
 * </pre>
 *
 * <p>Seed comes from the JSON file unless {@code --seed} is provided (CLI overrides JSON).
 */
public class CombatCliRunner {

    private static final String BOARD_PREFIX = "--board-json=";
    private static final String SEED_PREFIX = "--seed=";
    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * @return process exit code (0 = success)
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            CliArgs cli = parseArgs(args, err);
            if (cli == null) {
                return 1;
            }

            ReplayFileDto replay = loadReplay(cli.boardJsonPath, err);
            long seed = cli.seedOverride != null ? cli.seedOverride : replay.seed;

            Board[] boards = buildBoards(replay, err);
            CombatEngine engine = new CombatEngine(seed);
            ResolutionResult result = engine.resolve(boards[0], boards[1]);

            CombatResultDto output = CombatResultMapper.toDto(seed, result);
            out.println(GSON.toJson(output));
            return 0;
        } catch (CliException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static CliArgs parseArgs(String[] args, PrintStream err) {
        String boardJsonPath = null;
        Long seedOverride = null;

        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                printUsage(err);
                return null;
            }
            if (arg.startsWith(BOARD_PREFIX)) {
                boardJsonPath = arg.substring(BOARD_PREFIX.length());
                continue;
            }
            if (arg.startsWith(SEED_PREFIX)) {
                Long parsed = parseSeed(arg.substring(SEED_PREFIX.length()), err);
                if (parsed == null) {
                    return null;
                }
                seedOverride = parsed;
                continue;
            }
            err.println("Unknown argument: " + arg);
            printUsage(err);
            return null;
        }

        if (boardJsonPath == null || boardJsonPath.isBlank()) {
            err.println("Error: --board-json=<path> is required");
            printUsage(err);
            return null;
        }

        CliArgs cli = new CliArgs();
        cli.boardJsonPath = boardJsonPath;
        cli.seedOverride = seedOverride;
        return cli;
    }

    private static Long parseSeed(String value, PrintStream err) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            err.println("Error: invalid --seed value: " + value);
            return null;
        }
    }

    private static ReplayFileDto loadReplay(String boardJsonPath, PrintStream err) throws CliException {
        Path path = Path.of(boardJsonPath);
        if (!Files.isRegularFile(path)) {
            throw new CliException("board JSON file not found: " + path);
        }

        String json;
        try {
            json = Files.readString(path);
        } catch (IOException e) {
            throw new CliException("failed to read board JSON: " + e.getMessage());
        }

        try {
            ReplayFileDto replay = GSON.fromJson(json, ReplayFileDto.class);
            if (replay == null) {
                throw new CliException("board JSON is empty");
            }
            if (replay.players == null || replay.players.isEmpty()) {
                throw new CliException("board JSON must include a non-empty \"players\" array");
            }
            return replay;
        } catch (JsonSyntaxException e) {
            throw new CliException("invalid board JSON: " + e.getMessage());
        }
    }

    private static Board[] buildBoards(ReplayFileDto replay, PrintStream err) throws CliException {
        Board player0 = null;
        Board player1 = null;

        for (PlayerDto playerDto : replay.players) {
            if (playerDto.units == null) {
                playerDto.units = java.util.List.of();
            }
            try {
                Board board = BoardBuilder.buildBoard(playerDto);
                if (playerDto.id == 0) {
                    player0 = board;
                } else if (playerDto.id == 1) {
                    player1 = board;
                } else {
                    err.println("Warning: ignoring unknown player id " + playerDto.id);
                }
            } catch (IllegalArgumentException e) {
                throw new CliException("invalid board for player " + playerDto.id + ": " + e.getMessage());
            }
        }

        if (player0 == null || player1 == null) {
            throw new CliException("board JSON must include players with id 0 and id 1");
        }

        return new Board[] {player0, player1};
    }

    private static void printUsage(PrintStream err) {
        err.println("Usage: CombatCliRunner --board-json=<path> [--seed=<long>]");
        err.println();
        err.println("  --board-json=<path>   JSON file with seed and player unit placements");
        err.println("  --seed=<long>         Optional combat seed (overrides JSON seed)");
        err.println("  --help, -h            Show this message");
        err.println();
        err.println("Writes combat resolution JSON to stdout.");
    }

    private static final class CliArgs {
        String boardJsonPath;
        Long seedOverride;
    }

    private static final class CliException extends Exception {
        CliException(String message) {
            super(Objects.requireNonNull(message));
        }
    }
}
