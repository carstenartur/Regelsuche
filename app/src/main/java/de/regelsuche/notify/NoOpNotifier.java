package de.regelsuche.notify;

public class NoOpNotifier implements SimplificationNotifier {
    @Override
    public void onSignificantSimplification(String fromExpression, String toExpression) {
    }
}
