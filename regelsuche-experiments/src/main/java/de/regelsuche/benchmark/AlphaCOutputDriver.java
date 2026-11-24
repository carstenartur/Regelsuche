package de.regelsuche.benchmark;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Executes generated Alpha C over explicitly ordered, bounded source coordinates. */
final class AlphaCOutputDriver {
    private static final int MAX_INDEX = 512;
    private static final int MAX_POINTS = 16_384;
    private static final Pattern C_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");

    private AlphaCOutputDriver() {}

    static String source(String name, int rank) {
        checkRank(rank);
        if (!C_IDENTIFIER.matcher(name).matches()) throw new IllegalArgumentException("ALPHA_C_IDENTIFIER");
        return String.format(Locale.ROOT, """
            #include <errno.h>
            #include <limits.h>
            #include <stdio.h>
            #include <stdlib.h>
            #define RANK %d
            #define MAX_INDEX %d
            #define MAX_POINTS %d
            #define ALPHA_FUNCTION %s

            #if RANK == 1
            void ALPHA_FUNCTION(long, long*, long*);
            #else
            void ALPHA_FUNCTION(long, long*, long**);
            #endif

            static long read_nonnegative(const char *text, long maximum) {
                char *end;
                errno = 0;
                long value = strtol(text, &end, 10);
                if (errno || end == text || *end || value < 0 || value > maximum) return -1;
                return value;
            }

            int main(int argc, char **argv) {
                if (argc < 4 + RANK || (argc - 4) %% RANK || (argc - 4) / RANK > MAX_POINTS) return 2;
                if (sizeof(long) != 8) return 3;
                long parameter = read_nonnegative(argv[1], MAX_INDEX);
                long seed = read_nonnegative(argv[2], 2);
                long input_size = read_nonnegative(argv[3], MAX_INDEX + 1);
                if (parameter < 0 || seed < 0 || input_size < 1) return 4;

                long extents[] = {1, 1};
                for (int i = 4; i < argc; i++) {
                    long coordinate = read_nonnegative(argv[i], MAX_INDEX);
                    if (coordinate < 0) return 5;
                    int axis = (i - 4) %% RANK;
                    if (coordinate >= extents[axis]) extents[axis] = coordinate + 1;
                }
                long cells = extents[0] * extents[1];
                long *values = malloc((size_t)cells * sizeof(*values));
                long *input = malloc((size_t)input_size * sizeof(*input));
                if (!values || !input) {
                    free(values);
                    free(input);
                    return 6;
                }
                for (long i = 0; i < input_size; i++) input[i] = (i * 17 + seed * 7) %% 19 - 9;
                for (long i = 0; i < cells; i++) values[i] = LONG_MIN;
                #if RANK == 1
                ALPHA_FUNCTION(parameter, input, values);
                #else
                long *rows[MAX_INDEX + 1];
                for (long i = 0; i < extents[0]; i++) rows[i] = values + i * extents[1];
                ALPHA_FUNCTION(parameter, input, rows);
                #endif
                for (int i = 4; i < argc; i += RANK) {
                    long index = read_nonnegative(argv[i], MAX_INDEX);
                    #if RANK == 2
                    index = index * extents[1] + read_nonnegative(argv[i + 1], MAX_INDEX);
                    #endif
                    printf("%%ld\\n", values[index]);
                }
                free(values);
                free(input);
                return 0;
            }
            """, rank, MAX_INDEX, MAX_POINTS, name);
    }

    static List<String> command(Path binary, int parameter, int seed, int inputSize,
            List<List<Integer>> outputCoordinates) {
        if (parameter < 0 || parameter > MAX_INDEX || seed < 0 || seed > 2
                || inputSize < 1 || inputSize > MAX_INDEX + 1 || outputCoordinates.isEmpty()
                || outputCoordinates.size() > MAX_POINTS) throw new IllegalArgumentException("ALPHA_C_INPUT_LIMIT");
        int rank = outputCoordinates.getFirst().size();
        checkRank(rank);
        var command = new ArrayList<>(List.of(binary.toString(), Integer.toString(parameter), Integer.toString(seed),
            Integer.toString(inputSize)));
        List<Integer> previous = List.of();
        for (var point : outputCoordinates) {
            if (point.size() != rank || !inOrder(previous, point)) {
                throw new IllegalArgumentException("ALPHA_C_OUTPUT_ORDER");
            }
            for (int coordinate : point) {
                if (coordinate < 0 || coordinate > MAX_INDEX) throw new IllegalArgumentException("ALPHA_C_INDEX_LIMIT");
                command.add(Integer.toString(coordinate));
            }
            previous = point;
        }
        return List.copyOf(command);
    }

    private static boolean inOrder(List<Integer> previous, List<Integer> current) {
        for (int i = 0; i < previous.size(); i++) {
            int comparison = Integer.compare(previous.get(i), current.get(i));
            if (comparison != 0) return comparison < 0;
        }
        return previous.isEmpty();
    }

    private static void checkRank(int rank) {
        if (rank != 1 && rank != 2) throw new IllegalArgumentException("ALPHA_C_OUTPUT_RANK");
    }
}
