# Regelsuche client

Python 3.10+, no runtime dependencies. Install from the repository or the
Python directory included in the Regelsuche distribution:

```sh
python -m pip install ./python
```

With a running Workbench (`bin/regelsuche serve --port 8080`):

```python
from regelsuche import Client

client = Client("http://127.0.0.1:8080")
result = client.solve(["x+y=3", "x-y=1", "z=3"])
assert result.verified  # independently recomputed using Fraction
print(result.particular)  # {'x': Fraction(2), 'y': Fraction(1), 'z': Fraction(3)}
result.save("linear-solution.json")
client.replay(result)
```

Offline verification needs no Java process:

```sh
python -m regelsuche verify linear-solution.json
python -m regelsuche --url http://127.0.0.1:8080 solve -e 'x+y=3' -e 'x-y=1' --output solution.json
python -m regelsuche study --output study.json
```

The checker verifies the complete canonical rational affine solution set,
including nullspace bases and contradictions. It does not verify solver work,
learning provenance or Lean proofs. It rejects unsupported source syntax and
values beyond its explicit input and 4096-bit rational bounds.
Use explicit multiplication (`2*x`) and rational arithmetic.
Unsolved responses never set `verified`; CLI exit codes are 0 for verified
results, 2 for an unsolved computation and 1 for errors.

In notebooks, the same `Client` API returns exact `fractions.Fraction` values;
no notebook extension is required. HTTP Basic authentication can be supplied
through the optional `authorization` header. Redirects are refused.

This is a locally installable source package and wheel build, not a claim of
publication to PyPI. The repository's `docs/python-client.md` contains the
full setup and external Java example.
