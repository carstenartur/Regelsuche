"""Check the four mixed-stream rotations and collect their timing observations."""
import json
import sys
from pathlib import Path


def summarize(root):
    def observations(filename):
        return [json.loads((root / str(r) / filename).read_bytes()) for r in range(4)]

    def common(filename):
        runs = observations(filename)
        if any(run != runs[0] for run in runs[1:]):
            raise ValueError(f"rotations differ in {filename}")
        return runs[0]

    return {
        "mixed": common("mixed.json"),
        "replayByDepthCountAndWork": common("replay.json"),
        "elapsedNanosByRotation": observations("mixed-nanos.json"),
        "isolatedReplayNanosByRotation": observations("replay-nanos.json"),
    }


if __name__ == "__main__":
    print(json.dumps(summarize(Path(sys.argv[1])), indent=2))
