package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactLinearSolutionCertificateTest {
    @Test void deeplyRetainsImmutableTypedEvidence() {
        var rows=new ArrayList<>(List.of(1,0));
        var operations=new ArrayList<>(List.of(ExactRrefReduction.RowOperation.swap(0,1)));
        var part=new ExactLinearSolutionCertificate.Part(rows,operations);
        var parts=new ArrayList<>(List.of(part));
        var proof=new ExactLinearSolutionCertificate(ExactLinearSolutionCertificate.SCHEMA,parts);
        rows.clear();operations.clear();parts.clear();
        assertEquals(List.of(1,0),proof.parts().getFirst().sourceRows());
        assertEquals(1,proof.parts().getFirst().operations().size());
        assertThrows(UnsupportedOperationException.class,()->proof.parts().clear());
        assertThrows(UnsupportedOperationException.class,()->part.sourceRows().clear());
        assertThrows(UnsupportedOperationException.class,()->part.operations().clear());
    }
    @Test void typedOperationsCannotRepresentNoninvertibleSteps() {
        assertThrows(IllegalArgumentException.class,()->ExactRrefReduction.RowOperation.scale(0,Rational.ZERO));
        assertThrows(IllegalArgumentException.class,()->ExactRrefReduction.RowOperation.addMultiple(0,0,Rational.NEGATIVE_ONE));
        assertThrows(IllegalArgumentException.class,()->ExactRrefReduction.RowOperation.swap(0,0));
    }
}
