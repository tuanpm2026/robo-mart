"""Run repository tools without autofix on this UI dispatch's source delta."""
import json, pathlib, subprocess, sys
root = pathlib.Path(__file__).resolve().parents[3]
artifacts = pathlib.Path(__file__).resolve().parent
before = json.loads((artifacts/'start-sources.json').read_text())
files = sorted(str(f.relative_to(root)) for app in ['customer-website','admin-dashboard','shared'] for f in (root/'frontend'/app/'src').rglob('*') if f.is_file() and f.suffix in ['.ts','.vue','.css'] and (str(f.relative_to(root)) not in before or f.read_text() != before[str(f.relative_to(root))]))
(artifacts/'ui-changed-source-files.json').write_text(json.dumps(files,indent=2)+'\n')
kind = sys.argv[1]
if kind == 'prettier':
    commands = [(root/'frontend',['./node_modules/.bin/prettier','--check',*[str(root/f) for f in files if not f.startswith('frontend/shared/')], 'customer-website/package.json','admin-dashboard/package.json','admin-dashboard/index.html','package-lock.json'])]
    commands += [(root/'frontend',['./node_modules/.bin/prettier','--check','--config','customer-website/.prettierrc.json',*[str(root/f) for f in files if f.startswith('frontend/shared/')]])]
elif kind == 'oxlint':
    commands = [(root/'frontend'/app,['../node_modules/.bin/oxlint',*[str(root/f) for f in files if f.startswith('frontend/'+app+'/') and pathlib.Path(f).suffix in ['.ts','.vue']]]) for app in ['customer-website','admin-dashboard']]
    commands += [(root/'frontend',['./node_modules/.bin/oxlint',*[str(root/f) for f in files if f.startswith('frontend/shared/') and pathlib.Path(f).suffix == '.ts']])]
elif kind == 'eslint':
    commands = [(root/'frontend'/app,['../node_modules/.bin/eslint','--no-fix',*[str(root/f) for f in files if f.startswith('frontend/'+app+'/') and pathlib.Path(f).suffix in ['.ts','.vue']]]) for app in ['customer-website','admin-dashboard']]
else: raise SystemExit('Expected prettier, oxlint or eslint')
receipts=[]
for cwd,command in commands:
    print(json.dumps({'cwd':str(cwd.relative_to(root)),'command':command}),flush=True)
    result = subprocess.run(command,cwd=cwd)
    receipts.append({'cwd':str(cwd.relative_to(root)),'command':command,'exit_code':result.returncode})
(artifacts/(kind+'-commands.json')).write_text(json.dumps(receipts,indent=2)+'\n')
raise SystemExit(max(r['exit_code'] for r in receipts))
