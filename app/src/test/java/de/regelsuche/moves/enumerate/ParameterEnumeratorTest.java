package de.regelsuche.moves.enumerate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveParameterKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterEnumeratorTest {

    @Test
    void subtermEnumeratorFindsSubterms() {
        List<MoveParameter> parameters = new SubtermParameterEnumerator().enumerate("x - 1");
        List<String> values = parameters.stream().map(MoveParameter::value).toList();
        assertTrue(values.contains("x"), values.toString());
        assertTrue(values.contains("x - 1"), values.toString());
        assertTrue(parameters.stream().allMatch(p -> p.kind() == MoveParameterKind.SUBTERM));
    }

    @Test
    void repeatedSubexpressionEnumeratorFindsRepeatedSubterms() {
        List<MoveParameter> parameters =
                new RepeatedSubexpressionEnumerator().enumerate("x*(y+1) + z*(y+1)");
        List<String> values = parameters.stream().map(MoveParameter::value).toList();
        assertTrue(values.contains("y + 1"), values.toString());
        // A non-repeated subterm such as "x" must not be reported.
        assertTrue(values.stream().noneMatch(value -> value.equals("x")), values.toString());
    }

    @Test
    void smallConstantEnumeratorIsFiniteAndStable() {
        SmallConstantEnumerator enumerator = new SmallConstantEnumerator();
        List<MoveParameter> first = enumerator.enumerate("anything");
        List<MoveParameter> second = enumerator.enumerate("something-else");
        assertEquals(7, first.size());
        assertEquals(first, second);
        assertEquals(
                List.of("-3", "-2", "-1", "0", "1", "2", "3"),
                first.stream().map(MoveParameter::value).toList());
    }

    @Test
    void cancellationCandidateEnumeratorProducesPlusOneForXMinusOne() {
        List<MoveParameter> parameters = new CancellationCandidateEnumerator().enumerate("x - 1");
        assertTrue(parameters.stream().anyMatch(p -> p.value().equals("+1")), parameters.toString());
    }

    @Test
    void completeSquareEnumeratorRecognisesShiftAndResidue() {
        List<MoveParameter> parameters =
                new CompleteSquareParameterEnumerator().enumerate("x^2 + 6*x + 5");
        MoveParameter shift = parameters.stream()
                .filter(p -> p.name().equals("shift")).findFirst().orElseThrow();
        MoveParameter residue = parameters.stream()
                .filter(p -> p.name().equals("residue")).findFirst().orElseThrow();
        assertEquals("3", shift.value());
        assertEquals("-4", residue.value());
    }

    @Test
    void depth1EnumeratorIsDeterministic() {
        Depth1MoveEnumerator enumerator = new Depth1MoveEnumerator();
        List<Depth1MoveEnumerator.CandidateMove> first = enumerator.enumerate("x^2 + 6*x + 5");
        List<Depth1MoveEnumerator.CandidateMove> second = enumerator.enumerate("x^2 + 6*x + 5");
        assertEquals(first, second);
        assertTrue(first.stream().anyMatch(c -> c.enumeratorId().equals("complete-square")));
    }
}
