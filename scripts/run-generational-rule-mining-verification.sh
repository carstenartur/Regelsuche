#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -z "${REGELSUCHE_AUTHORITY_GITHUB_SHA:-}" ]]; then
  REGELSUCHE_AUTHORITY_GITHUB_SHA="$(git rev-parse HEAD)"
  export REGELSUCHE_AUTHORITY_GITHUB_SHA
fi

if [[ ! "$REGELSUCHE_AUTHORITY_GITHUB_SHA" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "REGELSUCHE_AUTHORITY_GITHUB_SHA must be a 40-digit Git SHA" >&2
  exit 2
fi

./gradlew --no-daemon --no-configuration-cache :app:test \
  --tests de.regelsuche.docs.GenerationalRuleMiningCampaignTest \
  --console=plain

CAMPAIGN="$ROOT_DIR/app/build/reports/generational-rule-mining/campaign.json"
AUDIT="$ROOT_DIR/app/build/reports/generational-rule-mining/cumulative-reachability-audit.json"

for artifact in "$CAMPAIGN" "$AUDIT"; do
  if [[ ! -s "$artifact" ]]; then
    echo "Missing generational rule-mining evidence: $artifact" >&2
    exit 3
  fi
done

python3 - "$CAMPAIGN" "$AUDIT" "$REGELSUCHE_AUTHORITY_GITHUB_SHA" <<'PY'
import json
import pathlib
import sys

campaign_path = pathlib.Path(sys.argv[1])
audit_path = pathlib.Path(sys.argv[2])
revision = sys.argv[3].lower()

campaign = json.loads(campaign_path.read_text(encoding="utf-8"))
audit = json.loads(audit_path.read_text(encoding="utf-8"))

if campaign.get("schema") != "regelsuche.generational-rule-mining-campaign/v1":
    raise SystemExit("unexpected campaign schema")
if audit.get("schema") != "regelsuche.generational-rule-mining-reachability-audit/v1":
    raise SystemExit("unexpected audit schema")
if campaign.get("repositoryRevision") != revision:
    raise SystemExit("campaign is not bound to the requested repository revision")
if audit.get("repositoryRevision") != revision:
    raise SystemExit("audit is not bound to the requested repository revision")
if not audit.get("decision", {}).get("passed", False):
    raise SystemExit("cumulative generation reachability audit did not pass")

print(f"campaign: {campaign_path}")
print(f"audit: {audit_path}")
print(f"repository revision: {revision}")
print(
    "activated rules: "
    f"{campaign.get('summary', {}).get('activatedRules', 'unknown')}"
)
PY
