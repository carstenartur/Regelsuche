package de.regelsuche.algebra;

public record QuadraticCoefficients(int quadratic, int linear, int constant, String variable) {
    public boolean isMonic() {
        return quadratic == 1;
    }
}
