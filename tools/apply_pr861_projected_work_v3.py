from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools/apply_pr861_projected_work_v2.py"
TEMPORARY = ROOT / "tools/.apply_pr861_projected_work_runtime.py"

script = SOURCE.read_text(encoding="utf-8")
old = '''old_default = ": divideRoundUp(entry.getValue(), quantum(entry.getKey()));"
new_default = ": projectedStageUnits(entry.getKey(), entry.getValue());"
if new_default not in canonical:
    if old_default not in canonical:
        raise RuntimeError("canonical default projection expression not found")
    canonical = canonical.replace(old_default, new_default, 1)
'''
new = '''new_default = ": projectedStageUnits(entry.getKey(), entry.getValue());"
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
if old not in script:
    raise RuntimeError("v2 bootstrap projection block not found")
TEMPORARY.write_text(script.replace(old, new, 1), encoding="utf-8")
try:
    runpy.run_path(str(TEMPORARY), run_name="__main__")
finally:
    TEMPORARY.unlink(missing_ok=True)
