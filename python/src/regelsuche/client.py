"""Small synchronous HTTP client with exact, independently verified results."""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from .verify import MAX_BYTES, VerificationError, loads, verify_artifact, verify_study


class ClientError(RuntimeError):
    """Transport or response-contract failure."""


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


@dataclass(frozen=True, init=False)
class LinearSolution:
    _json: str
    _math: dict | None

    def __init__(self, artifact: dict, expected_equations: list[str] | None = None):
        raw = json.dumps(artifact, ensure_ascii=False, allow_nan=False)
        parsed = loads(raw)
        if parsed.get("schema") != "regelsuche.linear-solution-artifact/v1":
            raise VerificationError("Unsupported solution artifact")
        evidence = parsed.get("evidence")
        if not isinstance(evidence, dict) or not isinstance(evidence.get("request"), dict) or not isinstance(evidence.get("result"), dict):
            raise VerificationError("Incomplete solution envelope")
        request, result = evidence["request"], evidence["result"]
        if request.get("schema") != "regelsuche.linear-solve-request/v1" or result.get("equations") != request.get("equations"):
            raise VerificationError("Unbound source equations")
        if expected_equations is not None and request.get("equations") != expected_equations:
            raise VerificationError("Response is not bound to requested equations")
        status = result.get("status")
        if status not in {"SOLVED", "BUDGET_INCONCLUSIVE", "DOMAIN_UNSUPPORTED", "NONLINEAR", "NOT_APPLICABLE", "INVALID_SOURCE", "INVALID_CERTIFICATE"}:
            raise VerificationError("Unknown solver status")
        if status != "SOLVED" and "solution" in result:
            raise VerificationError("Unsolved response claims a solution")
        object.__setattr__(self, "_math", verify_artifact(parsed, expected_equations) if status == "SOLVED" else None)
        object.__setattr__(self, "_json", raw)

    @property
    def artifact(self) -> dict:
        return loads(self._json)

    @property
    def status(self) -> str:
        return self.artifact["evidence"]["result"]["status"]

    @property
    def verified(self) -> bool:
        return self._math is not None

    @property
    def classification(self) -> str | None:
        return self._math["classification"] if self._math else None

    @property
    def particular(self) -> dict | None:
        if not self._math or self._math["particular"] is None:
            return None
        return dict(zip(self._math["variables"], self._math["particular"]))

    @property
    def basis(self) -> list[dict]:
        if not self._math:
            return []
        return [dict(zip(self._math["variables"], vector)) for vector in self._math["basis"]]

    def save(self, path: str | Path) -> None:
        Path(path).write_text(json.dumps(self.artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def __repr__(self) -> str:
        return f"LinearSolution(status={self.status!r}, independently_verified={self.verified}, classification={self.classification!r})"


class Client:
    def __init__(self, base_url: str = "http://127.0.0.1:8080", *, timeout: float = 30, authorization: str | None = None):
        url = urlsplit(base_url)
        if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise ValueError("Expected an HTTP(S) server URL without embedded credentials, query or fragment")
        if not math.isfinite(timeout) or not 0 < timeout <= 300:
            raise ValueError("timeout must be between 0 and 300 seconds")
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._authorization = authorization
        self._opener = build_opener(_NoRedirect())

    def _request(self, path: str, payload: dict | None = None) -> dict:
        headers = {"Accept": "application/json"}
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
            if len(data) > MAX_BYTES:
                raise ClientError("Request exceeds 4 MiB")
            headers["Content-Type"] = "application/json"
        if self._authorization:
            headers["Authorization"] = self._authorization
        request = Request(self.base_url + path, data=data, headers=headers, method="POST" if data is not None else "GET")
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                return loads(response.read(MAX_BYTES + 1))
        except HTTPError as error:
            with error:
                detail = error.read(2048).decode("utf-8", errors="replace")
            raise ClientError(f"HTTP {error.code}: {detail}") from error
        except (URLError, TimeoutError, OSError) as error:
            raise ClientError(f"Cannot reach Regelsuche: {error}") from error

    def solve(self, equations: list[str], *, route: str = "AUTO", max_work_units: int = 20_000) -> LinearSolution:
        source = list(equations)
        if not 1 <= len(source) <= 16 or any(not isinstance(text, str) or not 1 <= len(text) <= 512 for text in source):
            raise ValueError("Expected 1 to 16 bounded equation strings")
        if route not in {"AUTO", "DIRECT", "MATRIX", "BLOCKS"} or type(max_work_units) is not int or not 0 <= max_work_units <= 1_000_000:
            raise ValueError("Invalid route or work budget")
        artifact = self._request("/api/representations/solve", {
            "schema": "regelsuche.linear-solve-request/v1", "equations": source,
            "route": route, "maxWorkUnits": max_work_units})
        result = LinearSolution(artifact, source)
        request = result.artifact["evidence"]["request"]
        if request.get("route") != route or request.get("maxWorkUnits") != max_work_units:
            raise VerificationError("Response route or budget differs from request")
        return result

    def replay(self, artifact: dict | LinearSolution) -> LinearSolution:
        source = artifact.artifact if isinstance(artifact, LinearSolution) else artifact
        response = self._request("/api/representations/solve/replay", source)
        if response != source:
            raise VerificationError("Server replay changed the retained artifact")
        return LinearSolution(response)

    def study(self) -> dict:
        report = self._request("/api/representations/study")
        verify_study(report)
        return report
