# Frontend assessment independent review evidence

This evidence supports `docs/frontend-upgrade-assessment-2026-11-09.md` at baseline commit `4ec50bbaa04933c43a37066f88c293e056c24012`. It is local assessment evidence, not release approval or a verified current original-checkout build.

## Results

- The original assessment's 329 passing unit tests and build evidence are retained as `initial-unit.log` and `initial-build.log`.
- The independent control run passed the same 329 unit tests (`review-unit.log`, exit 0) and both type checks (`review-typecheck.log`, exit 0).
- Three additional behavioral assertions failed (`regression-probes.log`, exit 1): existing-item cart rollback keeps quantity 2/subtotal 20 instead of 1/10; a delayed older search overwrites the latest result; unchecking the last brand retains it in the next API request. These failures confirm defects. They do not mean the application passed the added checks.
- The probe was created temporarily in the customer test directory, executed, and removed in a `finally` block. Its exact text is retained as `regression-probes.spec.ts.txt`; it is intentionally outside ordinary test discovery.
- `initial-browser.cjs`, `initial-browser.json` and both `initial-*-mobile.png` files are copied from the original assessor's final fixture browser run. The reviewer inspected their contents/screenshots without rerunning that browser harness. All identities/API data are synthetic. No real Keycloak/backend write was exercised.
- `initial-audit*.json` are original npm registry audit snapshots, not fresh independent audits. Their package counts are not proof of shipped-browser exploitability.
- `review-provenance.json` identifies the baseline and hashes of selected files observed read-only in the dirty original checkout. These hashes are observations, not a tested release.

`evidence-sha256.json` records hashes of the evidence files. Absolute local paths and synthetic fixtures in logs are intentional provenance; no real user sessions or secrets are included.

## Reproduce the three behavioral failures

From the assessment worktree root with the locked frontend dependencies installed, use the following command. It refuses to overwrite an existing temporary test and always removes the test it creates. The expected outcome on the recorded baseline is **three failed assertions / exit 1**. A future fix should make the assertions pass; review the fixture after source-contract changes.

```bash
python3 - <<'PY'
from pathlib import Path
import subprocess
root = Path.cwd()
evidence = root / '_bmad-output/test-artifacts/frontend-upgrade-review-2026-11-09'
target = root / 'frontend/customer-website/src/stores/__tests__/frontendAssessmentReview.spec.ts'
with target.open('x') as f:
    f.write((evidence / 'regression-probes.spec.ts.txt').read_text())
try:
    result = subprocess.run([
        'npm', 'run', 'test:unit', '--workspace=customer-website', '--', '--run',
        'src/stores/__tests__/frontendAssessmentReview.spec.ts',
    ], cwd=root / 'frontend')
finally:
    target.unlink()
raise SystemExit(result.returncode)
PY
```

The original browser harness is archived for traceability. It requires the application previews on 15173/15174, locally installed `ws`/`axe-core`, macOS Chrome at its recorded path, and execution with cwd set to `frontend/`. It writes to `/tmp/robomart-assessment-*`; it is not a portable CI suite. Prefer the existing project Playwright effort when implementing regression coverage.
