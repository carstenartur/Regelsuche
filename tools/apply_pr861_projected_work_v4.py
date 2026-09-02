from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE_REVISION = "838fbbee2135128351bd68811a25c3b2e8d8581b"
SOURCE_PATH = "tools/apply_pr861_projected_work_v2.py"
TEMPORARY = ROOT / "tools/.apply_pr861_projected_work_runtime.py"

source = subprocess.run(
    ["git", "show", f"{SOURCE_REVISION}:{SOURCE_PATH}"],
    check=True,
    capture_output=True,
    text=True,
).stdout

old_projection_replacement = '''old_default = ": divideRoundUp(entry.getValue(), quantum(entry.getKey()));"
new_default = ": projectedStageUnits(entry.getKey(), entry.getValue());"
if new_default not in canonical:
    if old_default not in canonical:
        raise RuntimeError("canonical default projection expression not found")
    canonical = canonical.replace(old_default, new_default, 1)
'''
new_projection_replacement = '''new_default = ": projectedStageUnits(entry.getKey(), entry.getValue());"
if new_default not in canonical:
    import re
    canonical, replacement_count = re.subn(
        r":\\s*divideRoundUp\\(\\s*entry\\.getValue\\(\\),\\s*"
        r"quantum\\(entry\\.getKey\\(\\)\\)\\s*\\);",
        new_default,
        canonical,
        count=1,
    )
    if replacement_count != 1:
        raise RuntimeError("canonical default projection expression not found")
'''
if old_projection_replacement not in source:
    raise RuntimeError("historical projection bootstrap block not found")
source = source.replace(
    old_projection_replacement,
    new_projection_replacement,
    1,
)

characterization_start = source.index("characterization_relative = (")
characterization_end = source.index(
    'write(\n    "regelsuche-core/src/test/java/de/regelsuche/polynomial/'
    'PolynomialWorkProjectionTest.java"',
    characterization_start,
)
source = source[:characterization_start] + source[characterization_end:]

TEMPORARY.write_text(source, encoding="utf-8")
try:
    runpy.run_path(str(TEMPORARY), run_name="__main__")
finally:
    TEMPORARY.unlink(missing_ok=True)

projection_path = (
    ROOT
    / "regelsuche-core/src/main/java/de/regelsuche/polynomial/"
      "PolynomialWorkProjection.java"
)
projection = projection_path.read_text(encoding="utf-8")
old_projection_guard = '''        if (previous < 0L || next < previous) {
            throw new IllegalArgumentException(
                "polynomial work projection must be non-negative and monotone"
            );
        }
'''
new_projection_guard = '''        if (previous < 0L
                || next < previous
                || previous > previousRawUnits
                || next > nextRawUnits) {
            throw new IllegalArgumentException(
                "polynomial work projection must be non-negative, monotone "
                    + "and non-expansive"
            );
        }
'''
if old_projection_guard not in projection:
    raise RuntimeError("generated projection guard not found")
projection_path.write_text(
    projection.replace(old_projection_guard, new_projection_guard, 1),
    encoding="utf-8",
)

canonical_path = (
    ROOT
    / "regelsuche-experiments/src/main/java/de/regelsuche/benchmark/"
      "polynomial/PolynomialTheoryUtilityCanonicalWorkProjection.java"
)
canonical = canonical_path.read_text(encoding="utf-8")
old_runtime_projection = '''        public long projectStage(
            String stage,
            long cumulativeRawUnits
        ) {
            return projectedStageUnits(stage, cumulativeRawUnits);
        }
'''
new_runtime_projection = '''        public long projectStage(
            String stage,
            long cumulativeRawUnits
        ) {
            String retainedStage = Objects.requireNonNull(stage, "stage");
            if (!Dimension.FACTORIZATION.accepts(retainedStage)) {
                throw new IllegalArgumentException(
                    "native runtime projection received non-factorization "
                        + "stage: " + retainedStage
                );
            }
            return projectedStageUnits(retainedStage, cumulativeRawUnits);
        }
'''
if old_runtime_projection not in canonical:
    raise RuntimeError("generated native runtime projection not found")
canonical_path.write_text(
    canonical.replace(
        old_runtime_projection,
        new_runtime_projection,
        1,
    ),
    encoding="utf-8",
)

projection_test_path = (
    ROOT
    / "regelsuche-core/src/test/java/de/regelsuche/polynomial/"
      "PolynomialWorkProjectionTest.java"
)
projection_test = projection_test_path.read_text(encoding="utf-8")
projection_test_addition = '''
    @Test
    void rejectsAProjectionThatCreatesAdditionalWork() {
        PolynomialWorkProjection projection = new PolynomialWorkProjection() {
            @Override
            public String projectionId() {
                return "test.expansive/v1";
            }

            @Override
            public long projectStage(String stage, long cumulativeRawUnits) {
                return Math.addExact(cumulativeRawUnits, 1L);
            }
        };

        assertThrows(
            IllegalArgumentException.class,
            () -> projection.projectedDelta("stage", 0L, 1L)
        );
    }
'''
closing = projection_test.rfind("}\n")
if closing < 0:
    raise RuntimeError("projection test closing brace not found")
projection_test_path.write_text(
    projection_test[:closing]
    + projection_test_addition
    + projection_test[closing:],
    encoding="utf-8",
)

runtime_test_path = (
    ROOT
    / "regelsuche-experiments/src/test/java/de/regelsuche/benchmark/"
      "polynomial/PolynomialTheoryUtilityRuntimeProjectionParityTest.java"
)
runtime_test = runtime_test_path.read_text(encoding="utf-8")
runtime_test = runtime_test.replace(
    "import static org.junit.jupiter.api.Assertions.assertEquals;\n",
    "import static org.junit.jupiter.api.Assertions.assertEquals;\n"
    "import static org.junit.jupiter.api.Assertions.assertThrows;\n",
    1,
)
runtime_test_addition = '''
    @Test
    void runtimeProjectionRejectsAStageOwnedByAnotherDimension() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCanonicalWorkProjection
                .nativeRuntimeProjection()
                .projectStage("verify.product-reconstruction", 1L)
        );
    }
'''
closing = runtime_test.rfind("}\n")
if closing < 0:
    raise RuntimeError("runtime projection test closing brace not found")
runtime_test_path.write_text(
    runtime_test[:closing]
    + runtime_test_addition
    + runtime_test[closing:],
    encoding="utf-8",
)
