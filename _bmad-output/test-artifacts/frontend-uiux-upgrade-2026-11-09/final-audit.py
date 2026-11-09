"""Audit this dispatch's final artifacts/source without modifying application files."""
import collections, datetime, difflib, hashlib, json, pathlib, re, subprocess
root=pathlib.Path(__file__).resolve().parents[3]
artifacts=pathlib.Path(__file__).resolve().parent
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
read=lambda name:json.loads((artifacts/name).read_text())
start=read('start-snapshot.json'); before=read('start-sources.json')
previous={file:sha(root/file)==digest if (root/file).is_file() else False for file,digest in start['previous_evidence_sha256'].items()}
assert all(previous.values()), 'Prior evidence was changed or removed'
source=[]
for app in ['customer-website','admin-dashboard','shared']:
    for file in (root/'frontend'/app/'src').rglob('*'):
        if file.is_file() and file.suffix in ['.vue','.ts','.css']:
            relative=str(file.relative_to(root))
            if relative not in before or file.read_text()!=before[relative]: source.append(relative)
extra=['frontend/customer-website/package.json','frontend/admin-dashboard/package.json','frontend/admin-dashboard/index.html','frontend/package-lock.json','docs/frontend-upgrade-assessment-2026-11-09.md']
files=sorted(set(source+extra)); diff=[]
for relative in files:
    if relative=='frontend/package-lock.json':continue # Original full lockfile is in git; initial hash is recorded.
    diff.extend(difflib.unified_diff(before.get(relative,'').splitlines(keepends=True),(root/relative).read_text().splitlines(keepends=True),fromfile='a/'+relative,tofile='b/'+relative))
(artifacts/'ui-dispatch.diff.txt').write_text(''.join(diff))
(artifacts/'files-modified.txt').write_text('\n'.join(files)+ '\n'+str((artifacts/'README.md').relative_to(root))+'\n')
authoritative=['filter-focus-targeted-green','targeted-detail-final','targeted-admin-price-retry','final-unit-verified','final-type-verified','final-build-verified','eslint-verified','oxlint-verified','format-verified','final-shared-eslint','before-browser-settled','browser-completion-verified','interaction-verified-run','contrast-verified-run','contrast-baseline-settled-run']
for name in authoritative: assert read(name+'.json')['exit_code']==0, name
for name in ['filter-resize-red','filter-request-focus-red']: assert read(name+'.json')['exit_code']==1,name
receipts={}
for file in artifacts.glob('*.json'):
    value=json.loads(file.read_text())
    if isinstance(value,dict) and 'command'in value and 'exit_code'in value: receipts[file.name]=value
(artifacts/'validation-index.json').write_text(json.dumps({'authoritative_receipts':[name+'.json' for name in authoritative],'expected_red':['filter-resize-red.json','filter-request-focus-red.json'],'all_receipts':receipts},indent=2)+'\n')
full=read('after-browser-results.json'); focused=read('interaction-verified-browser-results.json')
for report in [full,focused]:
    assert not report['errors']
    assert all(screen['overflow']==0 for screen in report['screens'])
    assert all(not screen.get('axe',{}).get('violations') for screen in report['screens'])
    for interaction in report['interactions']:
        assert interaction.get('pass') is not False,interaction
        if 'expected' in interaction: assert interaction['actual']==interaction['expected'],interaction
    assert not any(i['name'] in ['pointer hit-test miss','baseline overflow fallback'] for i in report['interactions'])
for width in [360,390,768,1024,1440]:
    for name in ['customer-home','customer-search','customer-detail','customer-cart','checkout-review','checkout-shipping','checkout-shipping-errors','checkout-payment','admin-orders','admin-order-detail','admin-products','admin-product-drawer']:
        assert any(s['screen']==name and s['width']==width for s in full['screens']),(width,name)
contrast=read('contrast-verified-browser-results.json')['contrast']
assert contrast['iconRatio']>=3 and contrast['focusRatio']>=3 and contrast['focusVisible']
assert contrast['focusOutlineWidth']=='2px'
whitespace=subprocess.run(['git','diff','--check'],cwd=root,capture_output=True,text=True)
assert whitespace.returncode==0,whitespace.stdout+whitespace.stderr
lock=json.loads((root/'frontend/package-lock.json').read_text())
for app in ['customer-website','admin-dashboard']:
    manifest=json.loads((root/'frontend'/app/'package.json').read_text())
    assert manifest['dependencies']==lock['packages'][app]['dependencies']
    assert manifest['dependencies']['primeicons']=='7.0.0'
    assert manifest['dependencies']['@robo-mart/shared']=='*'
assert lock['packages']['node_modules/primeicons']['version']=='7.0.0'
for relative in ['frontend/customer-website/src/stores/useCartStore.ts','frontend/customer-website/src/stores/useProductStore.ts','frontend/customer-website/src/stores/__tests__/useCartStore.spec.ts','frontend/customer-website/src/stores/__tests__/useProductStore.search.spec.ts']:
    assert (root/relative).read_text()==before[relative],relative
assessment=(root/'docs/frontend-upgrade-assessment-2026-11-09.md').read_text()
assert before['docs/frontend-upgrade-assessment-2026-11-09.md'].partition('\n')[2] in assessment
# Check durable relative links in the new report; anchors/external links are separate.
missing=[]
for link in re.findall(r'\]\(([^)]+)\)',(artifacts/'README.md').read_text()):
    if not link.startswith(('https:','http:','#')) and not (artifacts/link.split('#')[0]).exists():missing.append(link)
assert not missing,missing
result={'timestamp_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'branch':subprocess.check_output(['git','branch','--show-current'],cwd=root,text=True).strip(),'starting_snapshot_sha256':sha(artifacts/'start-snapshot.json'),'files_modified':files,'source_sha256':{file:sha(root/file) for file in files},'previous_evidence_verified':len(previous),'previous_evidence_all_unchanged':all(previous.values()),'prior_cart_search_fix_sources_and_store_tests_unchanged':True,'assessment_baseline_body_preserved':True,'git_diff_check_exit_code':whitespace.returncode,'manifest_lock_contracts_match':True,'relative_report_links_exist':True,'browser':{'full_screens':len(full['screens']),'full_interactions':len(full['interactions']),'axe_audits':sum('axe'in s for s in full['screens']),'detected_violations':0,'document_overflow_observations':0,'focused_screens':len(focused['screens']),'focused_interactions':len(focused['interactions'])},'contrast':contrast,'dist_index_sha256':{app:sha(root/'frontend'/app/'dist/index.html') for app in ['customer-website','admin-dashboard']}}
result['report_sha256'] = sha(artifacts/'README.md')
result['cleanup_sha256'] = sha(artifacts/'cleanup.json')
result['browser_evidence_sha256'] = {file.name:sha(file) for file in artifacts.glob('*browser-results.json')}
result['screenshot_sha256'] = {file.name:sha(file) for file in artifacts.glob('*.png')}
(artifacts/'completion-manifest.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({key:result[key] for key in ['head','previous_evidence_verified','git_diff_check_exit_code','browser','contrast']},indent=2))
