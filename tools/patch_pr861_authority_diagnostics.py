from pathlib import Path

path = Path(
    "regelsuche-experiments/src/main/java/de/regelsuche/benchmark/"
    "polynomial/PolynomialTheoryUtilityCanonicalWorkProjection.java"
)
source = path.read_text(encoding="utf-8")
old = '''            throw new IllegalArgumentException(
                "projected work exceeds the frozen execution authority"
            );
'''
new = '''            throw new IllegalArgumentException(
                "projected work exceeds the frozen execution authority: "
                    + "input=" + input.inputId()
                    + ", primitive=" + work.primitiveWork()
                    + "/" + input.admittedPrimitiveWork()
                    + ", mechanical=" + work.mechanicalWork()
                    + "/" + input.totalMechanicalWork()
                    + ", factorization=" + work.factorizationWork()
                    + "/" + input.factorizationWork()
                    + ", breakdown=" + work
            );
'''
if old not in source:
    raise RuntimeError("authority exception block not found")
source = source.replace(old, new, 1)

old_call = '''        requireWithinAuthority(frozenInput, work);
'''
new_call = '''        try {
            requireWithinAuthority(frozenInput, work);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                exception.getMessage()
                    + ", matchingRaw=" + raw.matchingWork().stages()
                    + ", sourceValidationRaw="
                    + raw.sourceValidationWork().stages()
                    + ", factorizationRaw="
                    + raw.factorizationWork().stages(),
                exception
            );
        }
'''
if old_call not in source:
    raise RuntimeError("authority invocation not found")
source = source.replace(old_call, new_call, 1)
path.write_text(source, encoding="utf-8")
