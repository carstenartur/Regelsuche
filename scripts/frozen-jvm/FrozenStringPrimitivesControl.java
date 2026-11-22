import de.regelsuche.measure.FrozenStringPrimitives;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Compare the value and every interrupted write to Java's original arraycopy. */
public final class FrozenStringPrimitivesControl {
    public static void main(String[] args) {
        byte[] source = {2, 4, 8, 16, 32};
        byte[] expected = {7, 7, 7, 7, 7, 7, 7};
        System.arraycopy(source, 1, expected, 2, 3);
        for (int budget = 0; budget <= 6; budget++) {
            byte[] target = {7, 7, 7, 7, 7, 7, 7};
            List<String> admitted = new ArrayList<>();
            int limit = budget;
            FrozenStringPrimitives.begin(event -> {
                if (admitted.size() == limit) throw new IllegalStateException("exhausted");
                admitted.add(event);
            });
            try {
                FrozenStringPrimitives.arraycopy(source, 1, target, 2, 3);
                if (budget != 6) throw new AssertionError("unexpected completed copy");
            } catch (IllegalStateException error) {
                if (!"exhausted".equals(error.getMessage()) || budget == 6) throw error;
            } finally {
                FrozenStringPrimitives.end();
            }
            byte[] prefix = {7, 7, 7, 7, 7, 7, 7};
            System.arraycopy(source, 1, prefix, 2, budget / 2);
            if (!Arrays.equals(target, prefix) || budget == 6 && !Arrays.equals(target, expected))
                throw new AssertionError("wrong interrupted array state at " + budget);
            for (int i = 0; i < admitted.size(); i++)
                if (!admitted.get(i).equals(i % 2 == 0 ? "jdk.arraycopy.source-byte" : "jdk.arraycopy.target-byte"))
                    throw new AssertionError("event order differs");
        }
        byte[] shared = source.clone();
        var gaps = new ArrayList<String>();
        FrozenStringPrimitives.begin(gaps::add);
        try {
            FrozenStringPrimitives.arraycopy(shared, 0, shared, 1, 2);
            throw new AssertionError("overlapping shape was accepted");
        } catch (IllegalStateException expectedGap) {
            if (!Arrays.equals(shared, source) || !gaps.equals(List.of("gap.jdk.arraycopy-shape"))) throw expectedGap;
        } finally {
            FrozenStringPrimitives.end();
        }
        System.out.println("JDK native copy, all 7 budgets and untouched overlapping negative control agree");
    }
}
