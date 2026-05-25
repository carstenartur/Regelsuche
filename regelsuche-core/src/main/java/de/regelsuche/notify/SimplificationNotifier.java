package de.regelsuche.notify;

public interface SimplificationNotifier {
    void onSignificantSimplification(String fromExpression, String toExpression);
}
