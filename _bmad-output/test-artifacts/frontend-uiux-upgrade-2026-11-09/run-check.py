import datetime
import json
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[3]
output = root / '_bmad-output/test-artifacts/frontend-uiux-upgrade-2026-11-09'
name, *command = sys.argv[1:]
result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
(output / f'{name}.txt').write_text(result.stdout)
(output / f'{name}.json').write_text(json.dumps({
    'command': command,
    'cwd': str(Path.cwd().relative_to(root)),
    'exit_code': result.returncode,
    'timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
}, indent=2) + '\n')
print(result.stdout[-14000:])
print(f'{name}: exit {result.returncode}')
raise SystemExit(result.returncode)
