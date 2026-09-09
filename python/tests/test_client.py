import copy
import io
import json
import unittest
from pathlib import Path
from unittest.mock import Mock
from urllib.error import HTTPError, URLError
from regelsuche import Client, ClientError, VerificationError
from regelsuche.verify import load


class ClientTest(unittest.TestCase):
    def setUp(self):
        self.artifact = load(Path(__file__).resolve().parents[2] / "docs/examples/linear-solution.json")
        self.client = Client()
        self.client._opener.open = Mock(side_effect=lambda *a, **kw: io.BytesIO(json.dumps(self.artifact).encode()))

    def test_binds_request_and_returns_copies_with_exact_values(self):
        result = self.client.solve(["x+y=3", "x-y=1", "z=3"])
        self.assertTrue(result.verified)
        self.assertEqual(2, result.particular["x"])
        copy_of_artifact = result.artifact
        copy_of_artifact["evidence"]["result"]["status"] = "FORGED"
        self.assertEqual("SOLVED", result.status)
        self.assertTrue(self.client.replay(result).verified)
        request = self.client._opener.open.call_args.args[0]
        self.assertEqual("POST", request.method)
        with self.assertRaises(VerificationError):
            self.client.solve(["x=99"])

    def test_never_trusts_corrupted_or_unsolved_response(self):
        self.artifact["evidence"]["result"]["solution"]["particular"][0] = "42"
        with self.assertRaises(VerificationError):
            self.client.solve(["x+y=3", "x-y=1", "z=3"])
        self.artifact["evidence"]["result"].pop("solution")
        self.artifact["evidence"]["result"]["status"] = "BUDGET_INCONCLUSIVE"
        result = self.client.solve(["x+y=3", "x-y=1", "z=3"])
        self.assertFalse(result.verified)
        self.assertIsNone(result.particular)

    def test_bounds_and_transport_failures(self):
        for url in ("file:///tmp/data", "http://user:pass@example.com", "https://example.com/#fragment"):
            with self.assertRaises(ValueError):
                Client(url)
        with self.assertRaises(ValueError):
            Client(timeout=float("inf"))
        with self.assertRaises(ValueError):
            self.client.solve(["x=1"], max_work_units=True)
        for error in (URLError("offline"), HTTPError("http://127.0.0.1", 302, "redirect", {}, io.BytesIO(b"moved"))):
            self.client._opener.open.side_effect = error
            with self.assertRaises(ClientError):
                self.client.solve(["x=1"])
        self.client._opener.open.side_effect = lambda *a, **kw: io.BytesIO(b"[]")
        with self.assertRaises(VerificationError):
            self.client.solve(["x=1"])


if __name__ == "__main__":
    unittest.main()
