package de.regelsuche.notify;

public class ConsoleNotifier implements SimplificationNotifier {
    @Override
    public void onSignificantSimplification(String fromExpression, String toExpression) {
        System.out.println("Significant simplification found: " + fromExpression + " -> " + toExpression);
    }
}
