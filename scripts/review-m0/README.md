# Review M0 automatic acceptance

This directory is an acceptance-only boundary. It does not implement Review M0 production behavior.

## Entry point

From the repository root:

```powershell
scripts\review-m0\run-m0-acceptance.cmd
scripts\review-m0\run-m0-acceptance.cmd -Integrated
```

- Asset mode verifies the versioned schema, synthetic P0 fixtures, report contract, selector contract and static content boundary. A successful run reports `ASSET_VERIFIED`; it is not production evidence.
- Integrated mode reads `review-m0-integration-selector.json`. While that selector is `PENDING_INTEGRATION`, the command writes a `PENDING_INTEGRATION` report and exits non-zero.
- `AUTO_VERIFIED` is reserved for a future `READY` selector whose exact test classes and target methods all run with zero failures, errors or skipped tests, whose aggregate evidence has all 15 P0 targets passing and every negative gate at zero, and whose declared production roots pass the static sensitive-data scan.

## Integration adapter boundary

Do not make the harness guess future production class names. The integration owner adapts production tests by changing only the selector data:

1. Set a stable `adapterId` and `state` to `READY`.
2. List exact fully qualified JUnit `testClasses`.
3. Map every `M0-P0-001` through `M0-P0-015` to an exact `className` and `methodName`.
4. Declare repository-relative production `staticScanRoots` without traversal.
5. Have the selected tests write aggregate, content-free evidence to the system property `review.m0.acceptance.evidence.path` using `review-m0-integrated-evidence.schema.json`.

The evidence and report contain only counts, booleans, state identifiers and reason codes. They must never contain original document bytes/text, OCR text, object keys, hashes, personal identifiers or credentials.
