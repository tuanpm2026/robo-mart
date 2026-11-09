const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawn } = require('node:child_process')
const { createRequire } = require('node:module')
const { once } = require('node:events')
const { callPage } = require('./browser-call.cjs')
const modules = createRequire(path.join(process.cwd(), 'package.json'))
const WebSocket = modules('ws')

test('CDP helpers keep hostile labels and browser values as data', { timeout: 15000 }, async t => {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'robomart-cdp-test-'))
  const browser = spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', [
    '--headless=new', '--no-first-run', '--no-default-browser-check',
    '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank',
  ])
  let socket
  t.after(async () => {
    if (socket) socket.close()
    if (browser.exitCode === null) {
      const exited = once(browser, 'exit')
      browser.kill()
      await exited
    }
    fs.rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 })
  })
  const endpoint = await new Promise((resolve, reject) => {
    let output = ''
    browser.on('error', reject)
    browser.stderr.on('data', chunk => {
      output += chunk
      const match = output.match(/DevTools listening on (ws:\/\/[^\s]+)/)
      if (match) resolve(match[1])
    })
    browser.on('exit', () => reject(new Error('Chrome exited before CDP was ready')))
  })
  socket = new WebSocket(endpoint)
  await once(socket, 'open')
  let id = 0, session
  const pending = new Map()
  const send = (method, params = {}, sessionId = session) => new Promise((resolve, reject) => {
    const requestId = ++id
    pending.set(requestId, { resolve, reject })
    socket.send(JSON.stringify({ id: requestId, method, params, ...(sessionId ? { sessionId } : {}) }))
  })
  socket.on('message', raw => {
    const message = JSON.parse(raw)
    const request = pending.get(message.id)
    if (!request) return
    pending.delete(message.id)
    if (message.error) request.reject(new Error(message.error.message))
    else request.resolve(message.result)
  })
  const target = await send('Target.createTarget', { url: 'about:blank' }, null)
  session = (await send('Target.attachToTarget', { targetId: target.targetId, flatten: true }, null)).sessionId
  const hostile = "'); globalThis.injected = true; // \\\n\u2028"
  const point = { x: '0);globalThis.injected=true;//', y: 20 }
  const result = await callPage(send, (text, point) => {
    const button = document.createElement('button')
    button.textContent = text
    document.body.append(button)
    return { text: button.textContent, point, injected: globalThis.injected === true }
  }, hostile, point)
  assert.deepEqual(result, { text: hostile, point, injected: false })
  await assert.rejects(callPage(send, () => { throw new Error('Expected browser failure') }), /Expected browser failure/)
})
