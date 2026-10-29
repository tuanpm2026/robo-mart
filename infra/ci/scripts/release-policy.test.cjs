'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { execFileSync } = require('node:child_process')
const { components, resolveRelease, recordImage, readRecords, publishRelease } = require('./release-policy.cjs')

const sha = 'a'.repeat(40)
const prefix = 'ghcr.io/example/robomart'
const digest = `sha256:${'b'.repeat(64)}`
function apiFixture(eventName = 'workflow_run') {
  const run = { id: 71, workflow_id: 9, head_sha: sha, head_branch: 'main', event: 'push',
    status: 'completed', conclusion: 'success', head_repository: { full_name: 'example/robo-mart' } }
  const state = { run, status: 'ahead', requested: null, runs: [run], quality: [{ ...run, workflow_id: 19 }], summaries: ['CodeQL', 'Trivy'].map(name => ({ name, status: 'completed', conclusion: 'success' })) }
  const github = { rest: { actions: {
    getWorkflow: async ({ workflow_id }) => ({ data: { id: workflow_id === 'security-scan.yml' ? 19 : workflow_id === 'schema-compatibility.yml' ? 29 : 9 } }),
    listWorkflowRunsForRepo: async () => ({ data: { workflow_runs: state.quality } }),
    getWorkflowRun: async () => ({ data: state.run }),
    listWorkflowRuns: async args => { state.requested = args; return { data: { workflow_runs: state.runs } } },
  }, checks: { listForRef: async () => ({ data: { check_runs: state.summaries } }) }, repos: {
    getCommit: async ({ ref }) => { state.ref = ref; return { data: { sha } } },
    compareCommits: async () => ({ data: { status: state.status } }),
  } } }
  const context = { repo: { owner: 'example', repo: 'robo-mart' }, eventName,
    ref: 'refs/heads/main', payload: { workflow_run: { id: 71, head_sha: 'c'.repeat(40) } } }
  return { state, options: { github, context, kind: 'backend' } }
}

test('automatic release uses the actual successful CI head, not the workflow/default-branch SHA', async () => {
  const { options } = apiFixture()
  const result = await resolveRelease(options)
  assert.deepEqual(result, { sha, ciRunId: 71, tag: `main-${sha}`, prefix })
})

test('failed, unfinished, PR, fork, wrong-workflow and non-main CI runs cannot publish', async t => {
  for (const patch of [{ conclusion: 'failure' }, { status: 'in_progress' }, { event: 'pull_request' },
    { head_branch: 'feature' }, { workflow_id: 10 }, { head_repository: { full_name: 'fork/robo-mart' } }]) {
    await t.test(JSON.stringify(patch), async () => {
      const { options, state } = apiFixture(); Object.assign(state.run, patch)
      await assert.rejects(resolveRelease(options), /successful push CI/)
    })
  }
})

test('manual explicit SHA and default main head both require matching successful main push CI', async () => {
  for (const requestedSha of [sha, '']) {
    const { options, state } = apiFixture('workflow_dispatch')
    assert.equal((await resolveRelease({ ...options, requestedSha })).sha, sha)
    assert.equal(state.ref, requestedSha || 'main')
    assert.equal(state.requested.head_sha, sha)
    assert.equal(state.requested.workflow_id, 'ci-backend.yml')
    state.runs = []
    await assert.rejects(resolveRelease({ ...options, requestedSha }), /No successful/)
  }
})

test('manual short/ref/shell input and dispatch outside main are rejected', async () => {
  for (const requestedSha of ['abcdef0', 'main', 'a'.repeat(40) + '; command', '../main']) {
    await assert.rejects(resolveRelease({ ...apiFixture('workflow_dispatch').options, requestedSha }), /40-character/)
  }
  const { options } = apiFixture('workflow_dispatch'); options.context.ref = 'refs/heads/feature'
  await assert.rejects(resolveRelease(options), /main branch/)
})

test('even a passing commit cannot release if main no longer contains it', async () => {
  const { options, state } = apiFixture(); state.status = 'diverged'
  await assert.rejects(resolveRelease(options), /not on main/)
})

function temporary(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'robomart-release-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  return directory
}
function records(directory, kind, source = sha) {
  for (const name of components[kind].names) recordImage({ kind, sha: source, prefix, name, digest, directory })
}

test('digest records must contain exactly the expected images from the approved commit', t => {
  const directory = temporary(t); records(directory, 'frontend')
  assert.equal(readRecords({ directory, kind: 'frontend', sha, prefix }).length, 2)
  const file = path.join(directory, 'customer-website.json')
  const original = fs.readFileSync(file, 'utf8')
  for (const patch of [{ digest: 'latest' }, { sha: 'c'.repeat(40) }, { image: 'ghcr.io/attacker/image' }, { name: 'api-gateway' }]) {
    fs.writeFileSync(file, JSON.stringify({ ...JSON.parse(original), ...patch }))
    assert.throws(() => readRecords({ directory, kind: 'frontend', sha, prefix }), /does not match/)
  }
  fs.writeFileSync(file, original); fs.writeFileSync(path.join(directory, 'unexpected.json'), '{}')
  assert.throws(() => readRecords({ directory, kind: 'frontend', sha, prefix }), /unexpected/)
  fs.unlinkSync(path.join(directory, 'unexpected.json')); fs.unlinkSync(file)
  assert.throws(() => readRecords({ directory, kind: 'frontend', sha, prefix }), /Missing/)
})

function run(cwd, executable, args) {
  return execFileSync(executable, args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
}
function git(cwd, ...args) { return run(cwd, 'git', args) }
function commit(cwd, message) {
  git(cwd, 'add', '.'); git(cwd, '-c', 'user.name=Release test', '-c', 'user.email=release@example.invalid', 'commit', '-m', message)
  return git(cwd, 'rev-parse', 'HEAD')
}
function repository(t) {
  const root = temporary(t); const origin = path.join(root, 'origin.git'); const author = path.join(root, 'author')
  git(root, 'init', '--bare', '--initial-branch=main', origin)
  git(root, 'clone', origin, author)
  const production = path.join(author, 'infra/k8s/overlays/production'); fs.mkdirSync(production, { recursive: true })
  const names = [...components.backend.names, ...components.frontend.names]
  fs.writeFileSync(path.join(production, 'kustomization.yaml'), 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources: [workloads.yaml]\nimages:\n' + names.map(name => `- name: ghcr.io/robomart/${name}\n  newName: ${prefix}/${name}\n  newTag: previous\n`).join(''))
  fs.writeFileSync(path.join(production, 'workloads.yaml'), names.map(name => `apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: ${name}\nspec:\n  template:\n    spec:\n      containers:\n      - name: app\n        image: ghcr.io/robomart/${name}:latest\n`).join('---\n'))
  fs.mkdirSync(path.join(author, 'backend')); fs.writeFileSync(path.join(author, 'backend/source.txt'), 'initial')
  fs.mkdirSync(path.join(author, 'frontend')); fs.writeFileSync(path.join(author, 'frontend/source.txt'), 'initial')
  const source = commit(author, 'Source approved by CI'); git(author, 'push', 'origin', 'main')
  function clone(name) { const cwd = path.join(root, name); git(root, 'clone', origin, cwd); return cwd }
  function publishOptions(kind, cwd, candidate = source) {
    const directory = path.join(root, `records-${path.basename(cwd)}-${kind}`); records(directory, kind, candidate)
    return { kind, cwd, sha: candidate, prefix, directory }
  }
  return { root, origin, author, source, clone, publishOptions }
}

test('GitOps pins exact digests, preserves unrelated images, and repeats without a commit', t => {
  const repo = repository(t); const cwd = repo.clone('publisher')
  const options = repo.publishOptions('backend', cwd)
  assert.equal(publishRelease(options).status, 'published')
  const file = fs.readFileSync(path.join(cwd, 'infra/k8s/overlays/production/kustomization.yaml'), 'utf8')
  assert.equal((file.match(/digest: sha256:/g) || []).length, 7)
  assert.equal((file.match(/newTag: previous/g) || []).length, 2)
  assert.equal(publishRelease(options).status, 'unchanged')
  assert.equal(git(cwd, 'status', '--porcelain'), '')
})

test('a newer source change blocks an old build without changing GitOps', t => {
  const repo = repository(t); const cwd = repo.clone('publisher')
  fs.writeFileSync(path.join(repo.author, 'backend/source.txt'), 'newer')
  commit(repo.author, 'New backend'); git(repo.author, 'push', 'origin', 'main')
  const before = git(repo.author, 'rev-parse', 'HEAD')
  assert.equal(publishRelease(repo.publishOptions('backend', cwd)).status, 'superseded')
  assert.equal(git(repo.author, 'ls-remote', 'origin', 'refs/heads/main').split(/\s/)[0], before)
})

test('a newer published revision wins even if source files are identical', t => {
  const repo = repository(t); const old = repo.clone('older-publisher')
  fs.writeFileSync(path.join(repo.author, 'README.md'), 'A newer revision with identical app source')
  const newer = commit(repo.author, 'Documentation'); git(repo.author, 'push', 'origin', 'main')
  const current = repo.clone('newer-publisher')
  assert.equal(publishRelease(repo.publishOptions('backend', current, newer)).status, 'published')
  assert.equal(publishRelease(repo.publishOptions('backend', old)).status, 'superseded')
})

test('simultaneous frontend/backend pushes retry from fresh main and retain both digests', t => {
  const repo = repository(t); const backend = repo.clone('backend-job'); const frontend = repo.clone('frontend-job')
  let raced = false
  const backendOptions = repo.publishOptions('backend', backend)
  const command = (program, args) => {
    if (!raced && program === 'git' && args[0] === 'push') {
      raced = true; assert.equal(publishRelease(repo.publishOptions('frontend', frontend)).status, 'published')
    }
    return run(program === 'kustomize' && args[0] === 'edit'
      ? path.join(backend, 'infra/k8s/overlays/production') : backend, program, args)
  }
  const result = publishRelease({ ...backendOptions, command })
  assert.equal(result.attempts, 2)
  assert.equal((fs.readFileSync(path.join(backend, 'infra/k8s/overlays/production/kustomization.yaml'), 'utf8').match(/digest: sha256:/g) || []).length, 9)
})

test('a new source arriving during a push race prevents retrying the old release', t => {
  const repo = repository(t); const cwd = repo.clone('publisher'); let raced = false
  const command = (program, args) => {
    if (!raced && program === 'git' && args[0] === 'push') {
      raced = true; fs.writeFileSync(path.join(repo.author, 'backend/source.txt'), 'newest')
      commit(repo.author, 'New source during publication'); git(repo.author, 'push', 'origin', 'main')
    }
    return run(program === 'kustomize' && args[0] === 'edit'
      ? path.join(cwd, 'infra/k8s/overlays/production') : cwd, program, args)
  }
  assert.equal(publishRelease({ ...repo.publishOptions('backend', cwd), command }).status, 'superseded')
})

test('workflow wiring keeps trusted policy separate from approved source and publishes digests from this run', () => {
  const root = path.resolve(__dirname, '../../..')
  for (const file of ['cd-deploy.yml', 'cd-frontend.yml']) {
    const workflow = fs.readFileSync(path.join(root, '.github/workflows', file), 'utf8')
    assert.match(workflow, /ref: \$\{\{ needs\.resolve\.outputs\.sha \}\}/)
    assert.match(workflow, /ref: \$\{\{ github\.workflow_sha \}\}/)
    assert.match(workflow, /RELEASE_IMAGE_DIGEST: \$\{\{ steps\.image\.outputs\.digest \}\}/)
    assert.match(workflow, /uses: actions\/download-artifact@v4/)
    assert.doesNotMatch(workflow, /docker\/metadata-action|value=latest|SHORT_SHA/)
  }
})

test('the actual demo overlay routes Product HTTP through the image proxy and retains storage', () => {
  const root = path.resolve(__dirname, '../../..')
  const rendered = run(root, 'kustomize', ['build', 'infra/k8s/overlays/production'])
  const docs = rendered.split(/\n---\n/)
  const product = docs.find(item => /kind: Service\nmetadata:\n  name: product-service\n/.test(item))
  assert.ok(product)
  assert.match(product, /targetPort: image-proxy/)
  assert.match(rendered, /name: GOTMPDIR\n\s+value: \/build\/tmp/)
  assert.match(rendered, /name: product-image-proxy/)
  assert.match(rendered, /persistentVolumeReclaimPolicy: Retain/)
})


test('security/schema failures and failing SARIF summaries block release even when app CI passes', async () => {
  for (const type of ['security', 'schema', 'CodeQL']) {
    const { options, state } = apiFixture()
    if (type === 'security') state.quality[0].conclusion = 'failure'
    if (type === 'schema') state.quality.push({ ...state.run, workflow_id: 29, conclusion: 'failure' })
    if (type === 'CodeQL') state.summaries[0].conclusion = 'failure'
    await assert.rejects(resolveRelease(options), /failed for the release/)
  }
})

test('quality checks must complete, with a bounded wait and no bypass for manual dispatch', async () => {
  const { options, state } = apiFixture('workflow_dispatch')
  state.quality[0].status = 'in_progress'
  let waited = 0
  const wait = async () => { waited++; state.quality[0].status = 'completed' }
  assert.equal((await resolveRelease({ ...options, wait })).sha, sha)
  assert.equal(waited, 1)
  state.summaries = []
  assert.equal((await resolveRelease({ ...options, qualityAttempts: 1 })).sha, sha)
  state.quality = []
  await assert.rejects(resolveRelease({ ...options, qualityAttempts: 1 }), /missing or unfinished/)
})


test('image records reject symlinks and directories using the opened file descriptor', t => {
  const directory = temporary(t); records(directory, 'frontend')
  const file = path.join(directory, 'customer-website.json')
  const outside = path.join(temporary(t), 'record.json')
  fs.copyFileSync(file, outside); fs.unlinkSync(file); fs.symlinkSync(outside, file)
  assert.throws(() => readRecords({ directory, kind: 'frontend', sha, prefix }), { code: 'ELOOP' })
  fs.unlinkSync(file); fs.mkdirSync(file)
  assert.throws(() => readRecords({ directory, kind: 'frontend', sha, prefix }), /regular files/)
})
