# Review M0 automatic acceptance

This directory is an acceptance-only boundary. It does not implement Review M0 production behavior.

## Entry point

From the repository root:

```powershell
scripts\review-m0\run-m0-acceptance.cmd
scripts\review-m0\run-m0-acceptance.cmd -Integrated
```

- Asset mode verifies the versioned schema, synthetic P0 fixtures, report contract, selector contract and static content boundary. A successful run reports `ASSET_VERIFIED`; it is not production evidence.
- Integrated mode reads `review-m0-integration-selector.json`. The selector already contains the complete 15-target production-behavior adapter, but remains `PENDING_INTEGRATION` until its three named first-round capability commits are merged. In that state the command writes a precise `readinessMissing` list and exits non-zero without running the adapter.
- `AUTO_VERIFIED` is reserved for a future `READY` selector whose exact test classes and target methods all run with zero failures, errors or skipped tests, whose aggregate evidence has all 15 P0 targets passing and every frozen P0 counter at zero, and whose declared production roots pass the static sensitive-data scan.

## Integration adapter boundary

The R1-D adapter is `ReviewM0IntegratedEvidenceAdapter`. Every mapped method delegates to existing tests that execute Review M0 production classes; M0-P0-008 and M0-P0-009 additionally assert the two known first-round gaps directly. It writes only target status, assertion counts and the frozen P0 counters to the path supplied by `review.m0.acceptance.evidence.path`.

The integration owner must not set the selector to `READY` merely because the adapter compiles. After merging the three commits named in `pendingReasons`, rerun the adapter selection, verify every mapped method and the owner-aware production file chain, then change only `state` to `READY` and clear `pendingReasons`.

The selector contract requires:

1. A stable `adapterId`; `state=READY` only after every named dependency is present and behaviorally verified.
2. List exact fully qualified JUnit `testClasses`.
3. Map every `M0-P0-001` through `M0-P0-015` to an exact `className` and `methodName`.
4. Declare repository-relative production `staticScanRoots` without traversal.
5. Have the selected tests write aggregate, content-free evidence to the system property `review.m0.acceptance.evidence.path` using `review-m0-integrated-evidence.schema.json`.

The evidence and report contain only counts, booleans, state identifiers and reason codes. They must never contain original document bytes/text, OCR text, object keys, hashes, personal identifiers or credentials.

## Manual acceptance

Use `MANUAL_ACCEPTANCE.md` only after an Integrated run reports `AUTO_VERIFIED / MANUAL_PENDING`. This task never writes `HUMAN_ACCEPTED`; only the user may record that status after completing all applicable rows.
