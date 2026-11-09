"""Rebuild the captured pre-UI sources in a temporary directory without reverting the worktree."""
import hashlib, json, pathlib, shutil, subprocess, tempfile
root = pathlib.Path(__file__).resolve().parents[3]
artifacts = pathlib.Path(__file__).resolve().parent
snapshot = json.loads((artifacts / 'start-sources.json').read_text())
temporary = pathlib.Path(tempfile.mkdtemp(prefix='robomart-uiux-baseline-'))
for relative, source in snapshot.items():
    target = temporary / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(source)
frontend = temporary / 'frontend'
(frontend / 'node_modules').symlink_to(root / 'frontend/node_modules', target_is_directory=True)
for app in ['customer-website', 'admin-dashboard']:
    app_dir = frontend / app
    config = (root / 'frontend' / app / 'vite.config.ts').read_text()
    config = config.replace("alias: {", "alias: {\n      '@robo-mart/shared': " + json.dumps(str(frontend / 'shared/src/index.ts')) + ',')
    (app_dir / 'vite.config.ts').write_text(config)
    shutil.copytree(root / 'frontend' / app / 'public', app_dir / 'public')
    result = subprocess.run(['node', str(root / 'frontend/node_modules/vite/bin/vite.js'), 'build'], cwd=app_dir)
    if result.returncode: raise SystemExit(result.returncode)
metadata = {'temporary_directory': str(temporary), 'source_snapshot_sha256': hashlib.sha256((artifacts / 'start-sources.json').read_bytes()).hexdigest(), 'apps': ['customer-website', 'admin-dashboard'], 'purpose': 'Frozen pre-UI source browser rendering; temporary Vite alias for frozen shared source, no worktree source reverted'}
(artifacts / 'baseline-render.json').write_text(json.dumps(metadata, indent=2)+'\n')
print(json.dumps(metadata))
