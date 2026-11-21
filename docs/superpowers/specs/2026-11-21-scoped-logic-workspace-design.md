# Scoped logical exports in the existing workspace

## Intent and boundary

Issue #1020 already authorizes productizing verified generic snapshots through
the existing app, persistence and API. The scoped logical producer now exports
the same four-file contract, but the workspace rejects its descriptor. This
slice admits `scoped-logic@v1` as a retained source that users can inspect and
download with its original identities, assumptions, attempts and receipts.

The existing 512 KiB admission and exact descriptor, typed evidence and derived
handoff checks remain authoritative. Import performs no search, proof execution
or obligation parsing. A recorded certificate remains a recorded source claim.
The workspace has no configured independent kernel/rebuild receiver, so scoped
replay is unavailable and downstream assessments cannot issue positive proof,
novelty, promotion or release authority.

## Approach

The implementation choice is to extend the existing known-domain dispatch with
a descriptor obtained from the actual scoped domain in a non-executing metadata
context. This keeps one descriptor authority and the existing repository/routes.
Accepting arbitrary descriptors would weaken the known-contract boundary;
connecting a new proof worker would require a separate runtime and replay
contract. Neither is needed for this source-view increment.

The UI identifies logical context and recorded evidence, keeps downloads and
source inspection available, and explains the unavailable proof replay. The
three existing workspace domains retain their canonical view bytes and behavior.
This continues the authorized issue; the broader productization remains open.

## Verification

Use a real zero-depth scoped-domain export with a receiver that cannot run.
Check byte-preserving import/load/download, typed handoff, honest replay refusal,
blocked downstream authority, forged bindings, and existing-domain compatibility.
Exercise the actual HTTP route and browser import/reload/download path. Keep full
hosted CI and independent review required before integration.
