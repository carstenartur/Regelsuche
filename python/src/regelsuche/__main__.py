import argparse
import json
import sys
from pathlib import Path
from .client import Client, ClientError
from .verify import VerificationError, load, verify_artifact, verify_study


def main() -> int:
    parser = argparse.ArgumentParser(description="Regelsuche exact linear client and offline checker")
    parser.add_argument("--url", default="http://127.0.0.1:8080")
    commands = parser.add_subparsers(dest="command", required=True)
    solve = commands.add_parser("solve")
    solve.add_argument("-e", "--equation", action="append", required=True)
    solve.add_argument("--route", choices=["AUTO", "DIRECT", "MATRIX", "BLOCKS"], default="AUTO")
    solve.add_argument("--budget", type=int, default=20_000)
    solve.add_argument("--output", type=Path)
    verify = commands.add_parser("verify")
    verify.add_argument("artifact", type=Path)
    study = commands.add_parser("study")
    study.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "verify":
            artifact = load(args.artifact)
            if artifact.get("schema") == "regelsuche.representation-transfer-study/v1":
                print(json.dumps(verify_study(artifact)))
            else:
                checked = verify_artifact(artifact)
                print(json.dumps({"verified": True, "classification": checked["classification"]}))
            return 0
        client = Client(args.url)
        if args.command == "study":
            report = client.study()
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(verify_study(report)))
            return 0
        result = client.solve(args.equation, route=args.route, max_work_units=args.budget)
        if args.output:
            result.save(args.output)
        print(json.dumps({"status": result.status, "verified": result.verified,
            "classification": result.classification, "particular": result.particular, "basis": result.basis}, default=str))
        return 0 if result.verified else 2
    except (ClientError, VerificationError, ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
