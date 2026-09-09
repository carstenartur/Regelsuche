"""Installed wheel against a real Java server/CLI, in the same process namespace."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
from regelsuche import Client, ClientError
from regelsuche.verify import verify_artifact, verify_study


def main():
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    launcher = os.environ.get("REGELSUCHE_TEST_LAUNCHER")
    if launcher:
        command = [launcher]
    else:
        command = [os.environ.get("REGELSUCHE_TEST_JAVA", "java"), "-cp",
                   os.environ["REGELSUCHE_TEST_CLASSPATH"], "de.regelsuche.App"]
    with tempfile.TemporaryDirectory(prefix="regelsuche-live-") as directory:
        root = Path(directory)
        with (root / "server.log").open("w+") as log:
            process = subprocess.Popen(command + ["serve", "--port", str(port)], cwd=root, stdout=log, stderr=log)
            try:
                client = Client(f"http://127.0.0.1:{port}", timeout=5)
                deadline = time.monotonic() + 45
                while True:
                    if process.poll() is not None:
                        log.seek(0)
                        raise RuntimeError("Java server stopped: " + log.read()[-4000:])
                    try:
                        unique = client.solve(["x+y=3", "x-y=1", "z=3"])
                        break
                    except ClientError:
                        if time.monotonic() >= deadline:
                            raise
                        time.sleep(0.1)
                assert unique.verified and unique.particular == {"x": 2, "y": 1, "z": 3}
                path = root / "solution.json"
                unique.save(path)
                assert client.replay(unique).verified
                family = client.solve(["a+b=3", "c+d=5"])
                assert family.verified and len(family.basis) == 2
                contradiction = client.solve(["x=1", "x=2"])
                assert contradiction.verified and contradiction.classification == "INCONSISTENT"
                stopped = client.solve(["x=1"], max_work_units=0)
                assert not stopped.verified and stopped.status == "BUDGET_INCONCLUSIVE"
                nonlinear = client.solve(["x*y=1"])
                assert not nonlinear.verified and nonlinear.status == "NONLINEAR"
                report = client.study()
                counts = verify_study(report)
                assert counts == {"verified": 258, "unsolved": 12}
                cli = subprocess.run([sys.executable, "-m", "regelsuche", "verify", str(path)], capture_output=True, text=True, check=True)
                assert json.loads(cli.stdout)["verified"]
                exhausted = subprocess.run([sys.executable, "-m", "regelsuche", "--url", client.base_url,
                    "solve", "-e", "x=1", "--budget", "0"], capture_output=True, text=True)
                assert exhausted.returncode == 2
                print(json.dumps({"liveJava": True, "installedClient": True, "study": counts, "cli": True}))
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait()


if __name__ == "__main__":
    main()
