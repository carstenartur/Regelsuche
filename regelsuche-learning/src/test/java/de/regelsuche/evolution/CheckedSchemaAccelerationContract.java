package de.regelsuche.evolution;

import de.regelsuche.retention.RetainedGraph;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Executable without a JUnit runner; the JUnit adapter also runs this exact contract. */
final class CheckedSchemaAccelerationContract {
    private static long checks;
    private CheckedSchemaAccelerationContract() { }

    public static void main(String[] args) {
        runAll();
        System.out.println("checkedSchemaAccelerationChecks=" + checks);
    }

    static void runAll() {
        checks = 0;
        exactAndWildcardShapes();
        broadBucketRemovesImpossibleMatcherCalls();
        originalPrefixIsNotRefilled();
        allBitPositionsAndEmptyIndex();
        randomizedDifferential();
        immutableRequirementsAndOwnership();
        invalidInputs();
        sharedPaths();
    }

    private static void exactAndWildcardShapes() {
        int[][] rules = {{2, 3, 4}, {2, -1, 4}, {-1, -1, -1}, {1, -1, -1}};
        var index = new CheckedSchemaShapeIndex(5, rules);
        equal(7, index.candidates(2, 3, 4, 4));
        equal(6, index.candidates(2, 0, 4, 4));
        equal(4, index.candidates(2, 3, 0, 4));
        equal(12, index.candidates(1, 0, 0, 4));
        equal(0, index.candidates(2, 3, 4, 0));
    }

    private static void broadBucketRemovesImpossibleMatcherCalls() {
        // One root bucket, 32 different immediate child-shape pairs; not a learned benchmark.
        var requirements = new int[32][3];
        for (int i = 0; i < 32; i++) requirements[i] = new int[]{7, i / 4, i % 4};
        var index = new CheckedSchemaShapeIndex(8, requirements);
        for (int i = 0; i < 32; i++) {
            long candidates = index.candidates(7, i / 4, i % 4, 32);
            equal(1, Long.bitCount(candidates));
            equal(1L << i, candidates);
        }
    }

    private static void originalPrefixIsNotRefilled() {
        var index = new CheckedSchemaShapeIndex(3, new int[][]{{0, 0, 0}, {1, 1, 1}, {1, 1, 1}});
        equal(0, index.candidates(1, 1, 1, 1));
        equal(2, index.candidates(1, 1, 1, 2));
        equal(6, index.candidates(1, 1, 1, 3));
    }

    private static void allBitPositionsAndEmptyIndex() {
        var empty = new CheckedSchemaShapeIndex(1, new int[0][]);
        equal(0, empty.candidates(0, 0, 0, 0));
        var rules = new int[64][3];
        for (int i = 0; i < rules.length; i++) Arrays.fill(rules[i], i);
        var index = new CheckedSchemaShapeIndex(64, rules);
        for (int i = 0; i < 64; i++) {
            equal(1L << i, index.candidates(i, i, i, 64));
            equal(0, index.candidates(i, i, i, i));
        }
        Arrays.setAll(rules, i -> new int[]{-1, -1, -1});
        var wildcard = new CheckedSchemaShapeIndex(1, rules);
        equal(-1L, wildcard.candidates(0, 0, 0, 64));
        equal(Long.MAX_VALUE, wildcard.candidates(0, 0, 0, 63));
        equal(0, wildcard.candidates(0, 0, 0, 0));
    }

    private static void randomizedDifferential() {
        var random = new Random(20261030L);
        for (int trial = 0; trial < 160; trial++) {
            int shapeCount = 1 + random.nextInt(9);
            int[][] requirements = new int[random.nextInt(65)][3];
            for (int[] row : requirements) {
                for (int axis = 0; axis < 3; axis++) row[axis] = random.nextInt(shapeCount + 1) - 1;
            }
            var index = new CheckedSchemaShapeIndex(shapeCount, requirements);
            for (int query = 0; query < 30; query++) {
                int[] shape = {random.nextInt(shapeCount), random.nextInt(shapeCount), random.nextInt(shapeCount)};
                for (int prefix = 0; prefix <= requirements.length; prefix++) {
                    long expected = reference(requirements, shape, prefix);
                    equal(expected, index.candidates(shape[0], shape[1], shape[2], prefix));
                }
            }
        }
    }

    private static long reference(int[][] requirements, int[] shape, int prefix) {
        long result = 0;
        for (int row = 0; row < prefix; row++) {
            boolean possible = true;
            for (int axis = 0; axis < 3; axis++) {
                possible &= requirements[row][axis] == -1 || requirements[row][axis] == shape[axis];
            }
            if (possible) result |= 1L << row;
        }
        return result;
    }

    private static void immutableRequirementsAndOwnership() {
        int[][] requirements = {{0, 1, 2}};
        var index = new CheckedSchemaShapeIndex(3, requirements);
        requirements[0][0] = 2;
        equal(1, index.candidates(0, 1, 2, 1));
        equal(0, index.candidates(2, 1, 2, 1));
        var references = references(index);
        check(references.size() == 1 && references.getFirst() instanceof long[], "index must expose its actual mask array");
        check(references.getFirst() != requirements, "index must not retain mutable input rows");
        check(index.compilationWork() > 0, "compilation is paid");
    }

    private static void invalidInputs() {
        rejects(IllegalArgumentException.class, () -> new CheckedSchemaShapeIndex(0, new int[0][]));
        rejects(IllegalArgumentException.class, () -> new CheckedSchemaShapeIndex(1, new int[65][3]));
        rejects(IllegalArgumentException.class, () -> new CheckedSchemaShapeIndex(1, new int[][]{{0, 0}}));
        rejects(IllegalArgumentException.class, () -> new CheckedSchemaShapeIndex(1, new int[][]{{1, 0, 0}}));
        rejects(IllegalArgumentException.class, () -> new CheckedSchemaShapeIndex(1, new int[][]{{-2, 0, 0}}));
        var index = new CheckedSchemaShapeIndex(1, new int[][]{{-1, -1, -1}});
        rejects(IllegalArgumentException.class, () -> index.candidates(0, 0, 0, -1));
        rejects(IllegalArgumentException.class, () -> index.candidates(0, 0, 0, 2));
        rejects(IllegalArgumentException.class, () -> index.candidates(-1, 0, 0, 1));
        rejects(IllegalArgumentException.class, () -> index.candidates(0, 1, 0, 1));
    }

    private static void sharedPaths() {
        var root = SharedOccurrencePath.root();
        check(root.isEmpty(), "empty root path");
        var reference = new ArrayList<Integer>();
        var path = root;
        for (int depth = 0; depth < 64; depth++) {
            var previous = path;
            path = path.child(depth & 1);
            reference.add(depth & 1);
            check(references(path).getFirst() == previous, "one shared parent, no copied prefix");
            check(path.materialize().equals(reference), "path projection matches the original sequence");
            check(path.size() == depth + 1, "correct depth");
            for (int i = 0; i <= depth; i++) check(path.get(i).equals(reference.get(i)), "indexed access");
        }
        var left = path.child(0);
        var right = path.child(1);
        check(!left.materialize().equals(right.materialize()), "two occurrences cannot collapse");
        check(references(left).getFirst() == references(right).getFirst(), "siblings share only their prefix");
        check(root.isEmpty(), "root is unchanged");
        check(path.equals(path.materialize()), "List equality");
        var finalPath = path;
        rejects(IndexOutOfBoundsException.class, () -> finalPath.get(-1));
        rejects(IndexOutOfBoundsException.class, () -> finalPath.get(finalPath.size()));
        rejects(IllegalArgumentException.class, () -> root.child(2));
        rejects(IllegalArgumentException.class, () -> finalPath.copyInto(new Integer[1]));
        rejects(UnsupportedOperationException.class, () -> finalPath.set(0, 1));
        rejects(UnsupportedOperationException.class, () -> finalPath.materialize().add(0));
    }

    private static List<Object> references(RetainedGraph.View view) {
        var result = new ArrayList<Object>();
        view.retainedReferences(new RetainedGraph.Visitor() {
            @Override public void reference(Object value) { result.add(value); }
            @Override public void requireExact(Object value, Class<?> type) {
                check(value.getClass() == type, "exact owner type");
            }
        });
        return result;
    }
    private static void equal(long expected, long actual) {
        check(expected == actual, "expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); }
        catch (Throwable failure) {
            check(type.isInstance(failure), "unexpected exception: " + failure);
            return;
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }
}
