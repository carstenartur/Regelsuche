package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.search.moves.NativeNodeCountObjective;

/** Bounded input-only routing features; never a rewrite or proof authority. */
final class TaskKnowledgeProfile {
    private TaskKnowledgeProfile() { }
    record Profile(String key, long work) { }

    static Profile inspect(CheckedSchemaTaskSolver.Task task) {
        String objective = objectiveKey(task);
        if (objective == null) return new Profile("custom", 1);
        var text = new StringBuilder(objective).append(':').append(task.goal().maximumOutputScore()).append(':');
        int depth = task.goal().objective() instanceof StructuralTaskObjective ? 1 : 2;
        long work = append(task.source(), depth, text) + 2;
        return new Profile(text.toString(), work);
    }
    static String objectiveKey(CheckedSchemaTaskSolver.Task task) {
        if (task.goal().objective() == NativeNodeCountObjective.INSTANCE) return "nodes";
        return task.goal().objective() instanceof StructuralTaskObjective structure ? structure.key() : null;
    }
    static String goalKey(CheckedSchemaTaskSolver.Task task) {
        String objective = objectiveKey(task);
        return objective == null ? "custom" : objective + ":" + task.goal().maximumOutputScore();
    }
    private static long append(Expr expression, int depth, StringBuilder text) {
        if (expression instanceof BinaryExpr binary) {
            text.append(binary.operator().name());
            if (depth > 0) {
                text.append('(');
                long work = append(binary.left(), depth - 1, text);
                text.append(',');
                work += append(binary.right(), depth - 1, text);
                text.append(')');
                return work + 1;
            }
        } else text.append(expression instanceof NumberExpr ? 'N' : expression instanceof FunctionExpr ? 'F' : 'V');
        return 1;
    }
}
