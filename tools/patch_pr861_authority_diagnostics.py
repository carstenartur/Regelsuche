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
            );
'''
if old not in source:
    raise RuntimeError("authority exception block not found")
path.write_text(source.replace(old, new, 1), encoding="utf-8")
