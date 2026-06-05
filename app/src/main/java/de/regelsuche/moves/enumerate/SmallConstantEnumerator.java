package de.regelsuche.moves.enumerate;

import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveParameterKind;
import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates the finite set of small integer constants
 * {@code -3, -2, -1, 0, 1, 2, 3} as {@link MoveParameterKind#CONSTANT}
 * parameters. The result is independent of the input expression and always
 * stably sorted.
 */
public final class SmallConstantEnumerator implements ParameterEnumerator {

    private static final int[] CONSTANTS = {-3, -2, -1, 0, 1, 2, 3};

    @Override
    public String id() {
        return "small-constant";
    }

    @Override
    public List<MoveParameter> enumerate(String expression) {
        List<MoveParameter> parameters = new ArrayList<>(CONSTANTS.length);
        for (int index = 0; index < CONSTANTS.length; index++) {
            String value = Integer.toString(CONSTANTS[index]);
            parameters.add(new MoveParameter(
                    "c" + index,
                    MoveParameterKind.CONSTANT,
                    value,
                    value,
                    index,
                    id()));
        }
        return List.copyOf(parameters);
    }
}
