'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { execFileSync } = require('node:child_process')

const components = {
  backend: {
    workflow: 'ci-backend.yml',
    workflowName: 'Backend CI',
    names: ['api-gateway', 'product-service', 'cart-service', 'order-service',
      'inventory-service', 'payment-service', 'notification-service'],
    paths: ['backend/', 'infra/docker/', 'infra/ci/', '.github/workflows/ci-backend.yml',
      '.github/workflows/cd-deploy.yml'],
  },
  frontend: {
    workflow: 'ci-frontend.yml',
    workflowName: 'Frontend CI',
    names: ['customer-website', 'admin-dashboard'],
    paths: ['frontend/', 'infra/ci/', '.github/workflows/ci-frontend.yml',
      '.github/workflows/cd-frontend.yml'],
  },
}
const overlay = 'infra/k8s/overlays/production/kustomization.yaml'

function component(kind) {
  if (!Object.hasOwn(components, kind)) throw new Error('Unknown release component')
  return components[kind]
}

function requireSha(sha) {
  if (typeof sha !== 'string' || !/^[0-9a-f]{40}$/.test(sha)) {
    throw new Error('Release requires a full lowercase 40-character commit SHA')
  }
  return sha
}

function imagePrefix(repository) {
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository)) throw new Error('Invalid repository')
  return `ghcr.io/${repository.split('/')[0].toLowerCase()}/robomart`
}

function successfulMainRun(run, repository, workflowId, sha) {
  return run.head_sha === sha && run.workflow_id === workflowId
    && run.event === 'push' && run.head_branch === 'main'
    && run.status === 'completed' && run.conclusion === 'success'
    && run.head_repository?.full_name?.toLowerCase() === repository.toLowerCase()
}

async function waitForQuality({ github, context, sha, attempts = 24,
  wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)) }) {
  const { owner, repo } = context.repo
  const repository = `${owner}/${repo}`
  const { data: security } = await github.rest.actions.getWorkflow({ owner, repo, workflow_id: 'security-scan.yml' })
  const { data: schema } = await github.rest.actions.getWorkflow({ owner, repo, workflow_id: 'schema-compatibility.yml' })
  for (let attempt = 1; attempt <= attempts; attempt++) {
    const { data } = await github.rest.actions.listWorkflowRunsForRepo({ owner, repo, head_sha: sha,
      branch: 'main', event: 'push', per_page: 100 })
    const sameHead = data.workflow_runs.filter(run => run.head_sha === sha && run.event === 'push'
      && run.head_branch === 'main' && run.head_repository?.full_name?.toLowerCase() === repository.toLowerCase())
    const securityRun = sameHead.find(run => run.workflow_id === security.id)
    // Schema CI is path-filtered; if registered for this SHA it must also pass.
    const schemaRun = sameHead.find(run => run.workflow_id === schema.id)
    const runs = [securityRun, schemaRun].filter(Boolean)
    if (runs.some(run => run.status === 'completed' && run.conclusion !== 'success')) {
      throw new Error('Security/schema quality workflow failed for the release commit')
    }
    const { data: checks } = await github.rest.checks.listForRef({ owner, repo, ref: sha, per_page: 100 })
    const summaries = checks.check_runs.filter(check => ['CodeQL', 'Trivy'].includes(check.name))
    if (summaries.some(check => check.status === 'completed' && check.conclusion !== 'success')) {
      throw new Error('Code-scanning summary failed for the release commit')
    }
    if (securityRun && runs.every(run => run.status === 'completed' && run.conclusion === 'success')
        // SARIF summary checks are emitted for PRs, but may be absent on default-branch push CI.
        && summaries.every(check => check.status === 'completed' && check.conclusion === 'success')) return
    if (attempt === attempts) throw new Error('Quality checks are missing or unfinished; retry after CI completes')
    await wait(10000)
  }
}

/** Resolve once before granting package-write permission. Manual runs obey the same CI gate. */
async function resolveRelease({ github, context, kind, requestedSha = '', qualityAttempts = 24, wait }) {
  const cfg = component(kind)
  const { owner, repo } = context.repo
  const repository = `${owner}/${repo}`
  const { data: workflow } = await github.rest.actions.getWorkflow({ owner, repo, workflow_id: cfg.workflow })
  let sha
  let ciRunId
  if (context.eventName === 'workflow_run') {
    const id = context.payload.workflow_run?.id
    if (!Number.isSafeInteger(id)) throw new Error('Missing triggering CI run')
    const { data: run } = await github.rest.actions.getWorkflowRun({ owner, repo, run_id: id })
    sha = requireSha(run.head_sha)
    if (!successfulMainRun(run, repository, workflow.id, sha)) {
      throw new Error('Release requires successful push CI from this repository main branch')
    }
    ciRunId = run.id
  } else if (context.eventName === 'workflow_dispatch' && context.ref === 'refs/heads/main') {
    const ref = requestedSha === '' ? 'main' : requireSha(requestedSha)
    const { data: commit } = await github.rest.repos.getCommit({ owner, repo, ref })
    sha = requireSha(commit.sha)
    const { data } = await github.rest.actions.listWorkflowRuns({ owner, repo, workflow_id: cfg.workflow,
      head_sha: sha, branch: 'main', event: 'push', status: 'success', per_page: 100 })
    const run = data.workflow_runs.find(item => successfulMainRun(item, repository, workflow.id, sha))
    if (!run) throw new Error(`No successful ${cfg.workflowName} push run for ${sha}`)
    ciRunId = run.id
  } else {
    throw new Error('Release dispatch must use the main branch')
  }
  const { data: comparison } = await github.rest.repos.compareCommits({ owner, repo, base: sha, head: 'main' })
  if (!['identical', 'ahead'].includes(comparison.status)) throw new Error('Release commit is not on main')
  await waitForQuality({ github, context, sha, attempts: qualityAttempts, wait })
  return { sha, ciRunId, tag: `main-${sha}`, prefix: imagePrefix(repository) }
}

function validateRecord(record, kind, sha, prefix) {
  const cfg = component(kind)
  requireSha(sha)
  if (!record || !cfg.names.includes(record.name) || record.sha !== sha
      || record.image !== `${prefix}/${record.name}`
      || typeof record.digest !== 'string' || !/^sha256:[0-9a-f]{64}$/.test(record.digest)) {
    throw new Error('Image record does not match the approved source/image/digest')
  }
  return record
}

function recordImage({ kind, sha, prefix, name, digest, directory }) {
  const record = validateRecord({ name, sha, image: `${prefix}/${name}`, digest }, kind, sha, prefix)
  fs.mkdirSync(directory, { recursive: true })
  fs.writeFileSync(path.join(directory, `${name}.json`), JSON.stringify(record) + '\n', { flag: 'wx' })
  return record
}

function readRecords({ kind, sha, prefix, directory }) {
  const cfg = component(kind)
  const files = fs.readdirSync(directory).sort()
  const expected = cfg.names.map(name => `${name}.json`).sort()
  if (JSON.stringify(files) !== JSON.stringify(expected)) throw new Error('Missing or unexpected image records')
  return cfg.names.map(name => {
    const file = path.join(directory, `${name}.json`)
    // Open atomically without following links; inspect/read the same descriptor.
    const descriptor = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW)
    try {
      if (!fs.fstatSync(descriptor).isFile()) throw new Error('Image records must be regular files')
      const record = validateRecord(JSON.parse(fs.readFileSync(descriptor, 'utf8')), kind, sha, prefix)
      if (record.name !== name) throw new Error('Image record filename/name mismatch')
      return record
    } finally {
      fs.closeSync(descriptor)
    }
  })
}

/** Each attempt starts from fresh main; only our image entries are reapplied after a push race. */
function publishRelease({ kind, sha, prefix, directory, cwd = process.cwd(), attempts = 4,
  command = (program, args) => execFileSync(program, args, {
    cwd: program === 'kustomize' && args[0] === 'edit' ? path.join(cwd, path.dirname(overlay)) : cwd,
    encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
  }) }) {
  const cfg = component(kind)
  const records = readRecords({ kind, sha, prefix, directory })
  const provenance = `infra/k8s/overlays/production/${kind}-release.json`
  const git = (...args) => command('git', args).trim()
  if (git('status', '--porcelain', '--untracked-files=no')) throw new Error('GitOps checkout must be clean')
  for (let attempt = 1; attempt <= attempts; attempt++) {
    git('fetch', '--no-tags', 'origin', '+refs/heads/main:refs/remotes/origin/main')
    git('merge-base', '--is-ancestor', sha, 'origin/main')
    const changed = git('diff', '--name-only', sha, 'origin/main', '--', ...cfg.paths)
    if (changed) return { status: 'superseded', sha, changed: changed.split('\n') }
    git('checkout', '--quiet', '--detach', 'origin/main')
    const previousFile = path.join(cwd, provenance)
    if (fs.existsSync(previousFile)) {
      const previous = requireSha(JSON.parse(fs.readFileSync(previousFile, 'utf8')).source_sha)
      git('merge-base', '--is-ancestor', previous, 'origin/main')
      if (Number(git('rev-list', '--count', `${sha}..${previous}`)) > 0) {
        return { status: 'superseded', sha, previous }
      }
    }
    command('kustomize', ['edit', 'set', 'image', ...records.map(record =>
      `ghcr.io/robomart/${record.name}=${record.image}@${record.digest}`)])
    const changedFiles = git('diff', '--name-only').split('\n').filter(Boolean)
    if (changedFiles.some(file => file !== overlay)) throw new Error('Unexpected GitOps modification')
    if (!changedFiles.length) return { status: 'unchanged', sha }
    command('kustomize', ['build', 'infra/k8s/overlays/production'])
    fs.writeFileSync(path.join(cwd, provenance), JSON.stringify({ source_sha: sha, images: records }, null, 2) + '\n')
    git('add', '--', overlay, provenance)
    git('-c', 'user.name=github-actions[bot]', '-c', 'user.email=github-actions[bot]@users.noreply.github.com',
      'commit', '-m', `chore: deploy ${kind} ${sha} by digest [skip ci]`)
    try {
      git('push', 'origin', 'HEAD:refs/heads/main')
      return { status: 'published', sha, commit: git('rev-parse', 'HEAD'), attempts: attempt }
    } catch (failure) {
      const reason = String(failure.stderr || failure.message)
      if (attempt === attempts || !/non-fast-forward|fetch first|\[rejected\]/i.test(reason)) throw failure
    }
  }
  throw new Error('GitOps publication exhausted retries')
}

module.exports = { components, component, requireSha, imagePrefix, resolveRelease, waitForQuality, successfulMainRun,
  validateRecord, recordImage, readRecords, publishRelease }

if (require.main === module) {
  const [operation, kind, directory] = process.argv.slice(2)
  const sha = requireSha(process.env.RELEASE_SHA)
  const prefix = imagePrefix(process.env.GITHUB_REPOSITORY)
  if (operation === 'record') {
    recordImage({ kind, sha, prefix, directory, name: process.env.RELEASE_IMAGE_NAME,
      digest: process.env.RELEASE_IMAGE_DIGEST })
  } else if (operation === 'publish') {
    if (process.env.GITHUB_ACTIONS !== 'true') throw new Error('Publish CLI requires an isolated Actions checkout')
    console.log(JSON.stringify(publishRelease({ kind, sha, prefix, directory })))
  } else {
    throw new Error('Usage: release-policy.cjs {record|publish} {backend|frontend} RECORD_DIRECTORY')
  }
}
