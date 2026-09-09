import de.regelsuche.math.algorithms.linalg.LinearRepresentationPlanner;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationJson;
import java.util.List;

/** Run from an extracted release: java --class-path 'lib/*' examples/LinearSolve.java */
class LinearSolve {
    public static void main(String[] args) {
        var planner = new LinearRepresentationPlanner();
        var result = planner.solve(List.of("x+y=3", "x-y=1", "z=3"),
            LinearRepresentationPlanner.Route.AUTO, 20_000);
        var audit = planner.audit(result, LinearRepresentationJson.AUDIT_BUDGET);
        if (!audit.verified()) throw new IllegalStateException("No verified solution");
        System.out.print(LinearRepresentationJson.toJson(result, audit));
    }
}
