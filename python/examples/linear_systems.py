"""Run with the installed client and a local Workbench on port 8080."""
from pathlib import Path
from regelsuche import Client

client = Client()
unique = client.solve(["x+y=3", "x-y=1", "z=3"])
assert unique.verified
print(unique.particular)
unique.save(Path("linear-solution.json"))
assert client.replay(unique).verified
family = client.solve(["a+b=3", "c+d=5"])
assert family.verified and len(family.basis) == 2
print("Affine family:", family.particular, family.basis)
exhausted = client.solve(["x+y=3"], max_work_units=0)
assert exhausted.status == "BUDGET_INCONCLUSIVE" and not exhausted.verified
