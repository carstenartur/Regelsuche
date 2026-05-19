package de.regelsuche.example;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AlgebraicExampleGenerator {
    public List<String> generateSmallIntegerExamples(int min, int max) {
        Set<String> examples = new LinkedHashSet<>();
        for (int a = min; a <= max; a++) {
            if (a == 0) {
                continue;
            }
            int abs = Math.abs(a);
            examples.add("(x + " + abs + ")^2");
            examples.add("(x - " + abs + ")^2");
            examples.add("(x + " + abs + ")*(x - " + abs + ")");
            examples.add("x^2 + " + (2 * abs) + "*x + " + (abs * abs));
            examples.add("x^2 - " + (2 * abs) + "*x + " + (abs * abs));
            examples.add("x^2 + " + (2 * abs) + "*x");
            examples.add("x^2 - " + (2 * abs) + "*x");
        }
        for (int b = min; b <= max; b++) {
            for (int c = min; c <= max; c++) {
                examples.add("x^2 + " + b + "*x + " + c);
                examples.add("2*x^2 + " + b + "*x + " + c);
            }
        }
        return new ArrayList<>(examples);
    }
}
