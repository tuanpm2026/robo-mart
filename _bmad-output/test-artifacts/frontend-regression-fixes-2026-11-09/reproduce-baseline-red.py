"""Run the current regression tests against frozen baseline source in a temporary copy.

Run from any directory after installing the committed frontend dependency graph.
This script never swaps source in either checkout and never installs dependencies.
An exit status of 1 from Vitest is expected for the recorded baseline defects.
"""

import datetime
import hashlib
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

BASELINE_COMMIT = '4ec50bbaa04933c43a37066f88c293e056c24012'
ROOT = Path(__file__).resolve().parents[3]
OUTPUT = Path(__file__).resolve().parent
SOURCE = [
    'src/stores/useCartStore.ts',
    'src/stores/useProductStore.ts',
    'src/views/SearchResultsView.vue',
    'src/components/product/FilterSidebar.vue',
]
TESTS = [
    'src/stores/__tests__/useCartStore.spec.ts',
    'src/stores/__tests__/useProductStore.search.spec.ts',
    'src/views/__tests__/SearchResultsView.filters.spec.ts',
]
COMMAND = ['npm', 'run', 'test:unit', '--workspace=customer-website', '--', '--run', *TESTS]

with tempfile.TemporaryDirectory(prefix='robomart-frontend-red-') as temporary:
    frontend = Path(temporary) / 'frontend'
    frontend.mkdir()
    for name in ['package.json', 'package-lock.json']:
        shutil.copy2(ROOT / 'frontend' / name, frontend / name)
    for name in ['customer-website', 'shared']:
        shutil.copytree(
            ROOT / 'frontend' / name,
            frontend / name,
            ignore=shutil.ignore_patterns('node_modules', 'dist', 'coverage', '.env*'),
        )
    # npm resolves declared workspaces; the unchanged admin app only needs its manifest here.
    (frontend / 'admin-dashboard').mkdir()
    shutil.copy2(
        ROOT / 'frontend/admin-dashboard/package.json',
        frontend / 'admin-dashboard/package.json',
    )
    (frontend / 'node_modules').symlink_to(ROOT / 'frontend/node_modules', target_is_directory=True)
    for name in SOURCE:
        (frontend / 'customer-website' / name).write_bytes(subprocess.check_output(
            ['git', 'show', f'{BASELINE_COMMIT}:frontend/customer-website/{name}'], cwd=ROOT,
        ))
    result = subprocess.run(
        COMMAND, cwd=frontend, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    (OUTPUT / 'final-tests-baseline-red.txt').write_text(result.stdout)
    (OUTPUT / 'final-tests-baseline-red.json').write_text(json.dumps({
        'command': COMMAND,
        'cwd': 'isolated temporary frontend copy',
        'exit_code': result.returncode,
        'baseline_commit': BASELINE_COMMIT,
        'baseline_source_files': SOURCE,
        'test_sha256': {
            name: hashlib.sha256((ROOT / 'frontend/customer-website' / name).read_bytes()).hexdigest()
            for name in TESTS
        },
        'method': 'Final tests/config copied; four production files read from frozen Git commit; '
                  'installed locked dependencies reused; no source swaps in either checkout',
        'timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    }, indent=2) + '\n')
    for line in result.stdout.splitlines():
        if 'Test Files' in line or 'Tests ' in line:
            print(line)
    print('Baseline Vitest exit:', result.returncode)

raise SystemExit(result.returncode)
