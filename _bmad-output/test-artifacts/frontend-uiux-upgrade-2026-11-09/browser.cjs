// Run from frontend/: node ../_bmad-output/test-artifacts/frontend-uiux-upgrade-2026-11-09/browser.cjs before|after
// All API and identity requests are intercepted; previews use strict, task-specific ports.
const fs = require('node:fs')
const os = require('node:os')
const cp = require('node:child_process')
const path = require('node:path')
const crypto = require('node:crypto')
const { callPage } = require('./browser-call.cjs')
const { createRequire } = require('node:module')
const modules = createRequire(path.join(process.cwd(), 'package.json'))
const WebSocket = modules('ws')
const phase = process.argv[2]
if (!['before', 'after'].includes(phase)) throw new Error('Expected before or after')
const outputDir = __dirname
const outputPrefix = process.env.UIUX_OUTPUT_PREFIX || phase
const customer = process.env.UIUX_CUSTOMER_URL || 'http://127.0.0.1:16573'
const admin = process.env.UIUX_ADMIN_URL || 'http://127.0.0.1:16574'
const previewPorts = [new URL(customer).port, new URL(admin).port]
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'robomart-uiux-chrome-'))
const browser = cp.spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', [
  '--headless=new', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0',
  '--user-data-dir=' + profile, 'about:blank',
])
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const image = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><rect width="400" height="400" fill="#dbeafe"/><circle cx="200" cy="175" r="85" fill="#2563eb"/><path d="M95 320h210" stroke="#1e40af" stroke-width="25"/></svg>')
const longToken = 'RM' + '1234567890'.repeat(6)
const products = Array.from({ length: 8 }, (_, i) => ({
  id: i + 1, sku: longToken + i,
  name: i === 0 ? 'Wireless noise cancelling headphones with extended battery and travel case' : 'Everyday home and travel accessory ' + (i + 1),
  description: 'A detailed fixture description for keyboard, reflow and long-content inspection. '.repeat(8),
  price: 49.99, rating: 4, brand: 'Fixture Brand', stockQuantity: i === 7 ? 0 : 30,
  categoryName: 'Electronics and accessories', categoryId: 1, primaryImageUrl: image,
}))
const cart = { cartId: 'fixture-cart', items: products.slice(0, 2).map(p => ({ productId: p.id, productName: p.name, price: p.price, quantity: 1, subtotal: p.price, lineId: 'fixture-line-' + p.id })), totalItems: 2, totalPrice: 99.98 }
const order = { id: 1, userId: 'customer-' + longToken, status: 'CONFIRMED', itemCount: 2, totalAmount: 99.98, createdAt: '2026-11-09T12:00:00Z', updatedAt: '2026-11-09T12:00:00Z', shippingAddress: '123 Long Avenue, Apartment 1234, San Francisco, California 94105, United States', cancellationReason: null, items: cart.items.map(i => ({ ...i, unitPrice: i.price })), statusHistory: [{ status: 'CONFIRMED', changedAt: '2026-11-09T12:00:00Z' }] }
const summary = { ...order, items: undefined, statusHistory: undefined }
const now = Math.floor(Date.now() / 1000)
function user(isAdmin) {
  const claims = { sub: 'uiux-fixture', preferred_username: 'Fixture Administrator', given_name: 'Fixture', email: 'fixture@example.invalid', realm_access: { roles: isAdmin ? ['ADMIN'] : ['CUSTOMER'] }, exp: now + 7200, iat: now }
  return { id_token: 'synthetic', access_token: Buffer.from('{}').toString('base64url') + '.' + Buffer.from(JSON.stringify(claims)).toString('base64url') + '.synthetic', token_type: 'Bearer', scope: 'openid profile email', profile: claims, expires_at: now + 7200 }
}
async function run() {
  const endpoint = await new Promise((resolve, reject) => {
    let text = ''
    browser.stderr.on('data', chunk => { text += chunk; const match = text.match(/DevTools listening on (ws:\/\/[^\s]+)/); if (match) resolve(match[1]) })
    browser.on('exit', code => reject(new Error('Chrome exit ' + code + ': ' + text)))
  })
  const socket = new WebSocket(endpoint)
  await new Promise(resolve => socket.on('open', resolve))
  let sequence = 0, session
  const pending = new Map(), requests = [], errors = [], held = []
  const state = { apiError: false, holdOrder: false, orderStatus: 'CONFIRMED' }
  const send = (method, params = {}, sessionId = session) => new Promise((resolve, reject) => {
    const id = ++sequence; pending.set(id, { resolve, reject })
    socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }))
  })
  const fulfill = (requestId, body, code = 200, sessionId = session) => send('Fetch.fulfillRequest', { requestId, responseCode: code, responseHeaders: [{ name: 'Content-Type', value: 'application/json' }, { name: 'Access-Control-Allow-Origin', value: '*' }, { name: 'Access-Control-Allow-Headers', value: '*' }, { name: 'Access-Control-Allow-Methods', value: 'GET,POST,PUT,DELETE,OPTIONS' }], body: Buffer.from(JSON.stringify(body)).toString('base64') }, sessionId)
  socket.on('message', async raw => { try {
    const event = JSON.parse(raw)
    if (event.id) { const promise = pending.get(event.id); if (promise) { pending.delete(event.id); event.error ? promise.reject(new Error(event.error.message)) : promise.resolve(event.result) } return }
    if (event.method === 'Runtime.exceptionThrown') errors.push(event.params.exceptionDetails.text)
    if (event.method !== 'Fetch.requestPaused') return
    const { requestId, request } = event.params
    const url = new URL(request.url)
    if (previewPorts.includes(url.port) || url.protocol === 'data:') { await send('Fetch.continueRequest', { requestId }, event.sessionId); return }
    requests.push({ method: request.method, path: url.pathname, query: url.search })
    if (url.port !== '8080') { await send('Fetch.failRequest', { requestId, errorReason: 'BlockedByClient' }, event.sessionId); return }
    if (request.method === 'OPTIONS') { await fulfill(requestId, {}, 200, event.sessionId); return }
    if (state.apiError) { await fulfill(requestId, { error: { code: 'FIXTURE_ERROR', message: 'Fixture unavailable' } }, 500, event.sessionId); return }
    const page = Number(url.searchParams.get('page') || 0), size = Number(url.searchParams.get('size') || 20)
    let data = { data: [], traceId: 'uiux-fixture' }
    if (url.pathname.endsWith('/categories')) data = [{ id: 1, name: 'Electronics and accessories', description: 'Fixture category' }]
    else if (/\/products\/\d+$/.test(url.pathname)) data = { data: { ...products[0], category: { id: 1, name: 'Electronics and accessories' }, images: [{ id: 1, imageUrl: image, altText: products[0].name, displayOrder: 0 }] }, traceId: 'uiux-fixture' }
    else if (url.pathname.includes('/products')) data = { data: products.filter(p => !url.searchParams.has('brand') || p.brand === url.searchParams.get('brand')), pagination: { page, size, totalElements: 8, totalPages: 1 }, traceId: 'uiux-fixture' }
    else if (url.pathname.startsWith('/api/v1/cart')) data = { data: cart, traceId: 'uiux-fixture' }
    else if (/\/orders\/\d+$/.test(url.pathname)) data = { data: { ...order, status: state.orderStatus }, traceId: 'uiux-fixture' }
    else if (url.pathname.endsWith('/orders')) {
      if (request.method === 'POST') {
        if (state.holdOrder) { held.push({ requestId, sessionId: event.sessionId }); return }
        data = { data: { ...summary, status: 'PAYMENT_PENDING' }, traceId: 'uiux-fixture' }
      } else data = { data: [summary, { ...summary, id: 2, status: 'PAYMENT_PENDING' }], pagination: { page, size, totalElements: 2, totalPages: 1 }, traceId: 'uiux-fixture' }
    }
    await fulfill(requestId, data, 200, event.sessionId)
  } catch (error) { if (!error.message.includes('Invalid InterceptionId')) errors.push(error.message) } })
  const target = await send('Target.createTarget', { url: 'about:blank' }, null)
  session = (await send('Target.attachToTarget', { targetId: target.targetId, flatten: true }, null)).sessionId
  await send('Page.enable'); await send('Runtime.enable'); await send('Fetch.enable', { patterns: [{ urlPattern: '*' }] })
  await send('Page.addScriptToEvaluateOnNewDocument', { source: `window.__uiuxDocument=Math.random();if(location.port===${JSON.stringify(new URL(admin).port)})sessionStorage.setItem('oidc.user:http://localhost:8180/realms/robomart:robo-mart-admin',${JSON.stringify(JSON.stringify(user(true)))});if(location.port===${JSON.stringify(new URL(customer).port)})sessionStorage.setItem('oidc.user:http://localhost:8180/realms/robomart:robo-mart-frontend',${JSON.stringify(JSON.stringify(user(false)))});` })
  const evaluate = async expression => { const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }); if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails)); return result.result.value }
  const call = (fn, ...values) => callPage(send, fn, ...values)
  const waitFor = async (fn, values, label) => { for (let n = 0; n < 100; n++) { if (await call(fn, ...values)) return; await pause(50) } throw new Error('Timed out: ' + label) }
  const wait = async (expression, label = expression) => { for (let n = 0; n < 100; n++) { try { if (await evaluate(expression)) return } catch {} await pause(50) } throw new Error('Timed out: ' + label) }
  const resize = (width, height = 950) => send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
  const go = async (url, selector) => {
    const previous = await evaluate('window.__uiuxDocument')
    await send('Page.navigate', { url })
    await waitFor((previous, selector) => window.__uiuxDocument !== previous && !!document.querySelector(selector), [previous, selector], url + ' ' + selector)
    await evaluate('document.fonts.ready.then(()=>true)')
  }
  const click = async selector => {
    await waitFor(selector => !!document.querySelector(selector), [selector], selector)
    const point = await call(selector => { const e = document.querySelector(selector); if (!e) throw Error('Missing ' + selector); e.scrollIntoView({block:'center'}); const r = e.getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2} }, selector)
    if (!await call((selector, point) => document.querySelector(selector).contains(document.elementFromPoint(point.x, point.y)), selector, point)) {
      const hit = await call((selector, point) => { const e = document.querySelector(selector); return {target:e.outerHTML,hit:document.elementFromPoint(point.x,point.y)?.outerHTML,checked:e.checked,labelControl:e.control?.outerHTML,point} }, selector, point)
      report.interactions.push({name:'pointer hit-test miss',selector,phase,...hit})
      if(phase==='after') throw Error('Pointer target obscured: '+selector+' '+JSON.stringify(hit))
      await call(selector => document.querySelector(selector).focus(), selector); await key('Enter')
      report.interactions.push({name:'baseline overflow fallback',selector,method:'native focus + Enter'})
      return
    }
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', ...point })
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, ...point })
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, ...point })
  }
  // Baseline may need keyboard activation where its overflow hides controls. After uses real pointer clicks.
  const clickText = async text => {
    const selector = await call(text => { const e = [...document.querySelectorAll('button,a')].find(e => e.textContent.trim() === text); if (!e) throw Error('Missing text ' + text); e.dataset.uiuxTarget = 'true'; return '[data-uiux-target="true"]' }, text)
    if(phase==='before') { await call(selector => document.querySelector(selector).focus(), selector); await key('Enter') } else await click(selector)
    await evaluate(`document.querySelector('[data-uiux-target]')?.removeAttribute('data-uiux-target')`)
  }
  const key = async (name, shift = false) => {
    const map = { Tab: ['Tab', 9, ''], Enter: ['Enter', 13, '\r'], Space: ['Space', 32, ' '], Escape: ['Escape', 27, ''] }
    const [code, keyCode, text] = map[name]
    await send('Input.dispatchKeyEvent', { type: 'keyDown', key: name === 'Space' ? ' ' : name, code, windowsVirtualKeyCode: keyCode, modifiers: shift ? 8 : 0, ...(text ? { text } : {}) })
    await send('Input.dispatchKeyEvent', { type: 'keyUp', key: name === 'Space' ? ' ' : name, code, windowsVirtualKeyCode: keyCode, modifiers: shift ? 8 : 0 })
  }
  const fill = async (selector, text) => { await call(selector => {const e=document.querySelector(selector);e.focus();e.select()}, selector); await send('Input.insertText', { text }) }
  const metrics = () => evaluate(`(()=>{const box=s=>{const e=document.querySelector(s);if(!e)return null;const b=e.getBoundingClientRect();return {x:b.x,y:b.y,width:b.width,height:b.height,clientWidth:e.clientWidth,scrollWidth:e.scrollWidth,columns:getComputedStyle(e).gridTemplateColumns}};return {path:location.pathname,configuredWidth:screen.width,layoutWidth:innerWidth,layoutHeight:innerHeight,clientWidth:document.documentElement.clientWidth,documentWidth:document.documentElement.scrollWidth,overflow:document.documentElement.scrollWidth-document.documentElement.clientWidth,homeGrid:box('.home__grid'),searchGrid:box('.search-results__grid'),card:box('.product-card'),searchInput:box('.search-bar input'),sidebar:box('.admin-sidebar'),table:box('.p-datatable-table-container'),drawer:box('[data-pc-name="drawer"]'),form:box('.ssa__form')||box('.sp__form'),active:{tag:document.activeElement?.tagName,id:document.activeElement?.id,name:document.activeElement?.getAttribute('aria-label')},icons:[...document.querySelectorAll('.pi')].slice(0,3).map(e=>({class:e.className,content:getComputedStyle(e,':before').content,font:getComputedStyle(e,':before').fontFamily})),offenders:[...document.querySelectorAll('main *')].filter(e=>e.getBoundingClientRect().right>document.documentElement.clientWidth+1&&e.getBoundingClientRect().width>0).slice(0,8).map(e=>({tag:e.tagName,class:e.className,width:e.getBoundingClientRect().width,right:e.getBoundingClientRect().right}))}})()`)
  const screenshot = async name => { await evaluate('scrollTo(0,0)'); const result = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false }); const file = `${outputPrefix}-${name}.png`; fs.writeFileSync(path.join(outputDir, file), Buffer.from(result.data, 'base64')); return file }
  const axe = async () => { await evaluate(fs.readFileSync(modules.resolve('axe-core/axe.min.js'), 'utf8')); return evaluate(`axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21aa','wcag22aa']}}).then(r=>({violations:r.violations.map(v=>({id:v.id,impact:v.impact,nodes:v.nodes.map(n=>({target:n.target,summary:n.failureSummary})),helpUrl:v.helpUrl})),incomplete:r.incomplete.map(v=>({id:v.id,nodes:v.nodes.length}))}))`) }
  const report = { phase, variant: process.env.UIUX_INTERACTIONS_ONLY ? 'focused interaction rerun' : 'full width matrix', timestamp: new Date().toISOString(), browser: await send('Browser.getVersion', {}, null), harnessSha256: crypto.createHash('sha256').update(fs.readFileSync(__filename)).digest('hex'), fixture: 'Synthetic API/OIDC; all non-preview requests intercepted; no real backend/identity writes', screens: [], interactions: [], errors }
  const settle = async () => { await evaluate(`Promise.all(document.getAnimations().filter(a=>Number.isFinite(a.effect.getComputedTiming().endTime)).map(a=>a.finished.catch(()=>{}))).then(()=>document.fonts.ready).then(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))))`); }
  const capture = async (name, width, audit = true) => { await settle(); const item = { screen: name, width, ...await metrics() }; if ([390, 1440].includes(width) || name.startsWith('reflow-equivalent')) { item.screenshot = await screenshot(`${name}-${width}`); if (audit) item.axe = await axe() } report.screens.push(item) }
  const checkout = async width => { await go(customer + '/cart', '.cart-item'); await wait(`!!document.querySelector('.header__user-name')`); await click('.cart-summary__checkout-btn'); await wait(`!!document.querySelector('.scr__continue')`); await capture('checkout-review', width); await click('.scr__continue'); await wait(`!!document.querySelector('#fullName') && document.querySelector('#fullName').getBoundingClientRect().height>0`); await capture('checkout-shipping', width); await clickText('Continue to Payment →'); await wait(`!!document.querySelector('.ssa .p-error, .ssa .field-error')`); await capture('checkout-shipping-errors', width); for (const [field, value] of Object.entries({ fullName: 'Fixture Customer', street: '123 Long Avenue Apartment 1234', city: 'San Francisco', state: 'California', postalCode: '94105', country: 'US' })) await fill('#' + field, value); await wait(`document.querySelectorAll('.ssa .p-error,.ssa .field-error').length===0`); await settle(); await clickText('Continue to Payment →'); await wait(`!!document.querySelector('#cardNumber') && document.querySelector('#cardNumber').getBoundingClientRect().height>0`); await capture('checkout-payment', width) }
  try {
    if(process.env.UIUX_PROBE_ONLY) {
      await resize(390); await go(admin+'/admin/orders','.p-datatable-tbody'); await settle()
      await evaluate(`document.querySelector('button[title="View order details"]').focus()`); await key('Tab'); await key('Tab',true); await settle()
      report.contrast = await evaluate(`(()=>{const button=document.querySelector('button[title="View order details"]'),icon=button.querySelector('.p-button-icon');let ancestor=button;while(ancestor&&getComputedStyle(ancestor).backgroundColor==='rgba(0, 0, 0, 0)')ancestor=ancestor.parentElement;const s=getComputedStyle(button);return {foreground:getComputedStyle(icon).color,background:getComputedStyle(ancestor).backgroundColor,focusOutline:s.outlineColor,focusOutlineWidth:s.outlineWidth,focusOutlineOffset:s.outlineOffset,focusVisible:button.matches(':focus-visible'),boxShadow:s.boxShadow,border:s.borderColor,borderWidth:s.borderWidth,buttonLabel:button.getAttribute('aria-label')}})()`)
      await capture('order-action-contrast',390)
      const luminance = color => {
        const channels = color.match(/[0-9.]+/g).slice(0,3).map(Number).map(value=>value/255).map(value=>value<=0.04045?value/12.92:((value+0.055)/1.055)**2.4)
        return channels[0]*0.2126+channels[1]*0.7152+channels[2]*0.0722
      }
      const ratio = (a,b) => { const x=luminance(a),y=luminance(b);return (Math.max(x,y)+0.05)/(Math.min(x,y)+0.05) }
      report.contrast.iconRatio = ratio(report.contrast.foreground,report.contrast.background)
      report.contrast.focusRatio = report.contrast.focusOutline.includes('rgba')?null:ratio(report.contrast.focusOutline,report.contrast.background)
      if(process.env.UIUX_REQUIRE_CONTRAST && !(report.contrast.iconRatio>=3&&report.contrast.focusRatio>=3&&report.contrast.focusVisible&&parseFloat(report.contrast.focusOutlineWidth)>=2)) throw Error('Non-text contrast/focus assertion failed: '+JSON.stringify(report.contrast))
      console.log(JSON.stringify(report.contrast))
      return
    }
    for (const width of (process.env.UIUX_INTERACTIONS_ONLY ? [] : [360, 390, 768, 1024, 1440])) {
      await resize(width)
      await go(customer + '/', '.product-card'); await capture('customer-home', width)
      await go(customer + '/search?keyword=wireless', '.product-card'); await capture('customer-search', width)
      await go(customer + '/products/1', '.product-detail__title'); await capture('customer-detail', width)
      await go(customer + '/cart', '.cart-item'); await capture('customer-cart', width)
      await checkout(width)
      await go(admin + '/admin/orders', '.p-datatable-tbody'); await capture('admin-orders', width)
      if(phase==='after') report.interactions.push({name:'orders initial action visible '+width, actual:await evaluate(`(()=>{const e=document.querySelector('button[title="View order details"]');const r=e.getBoundingClientRect();return r.x>=0&&r.right<=innerWidth&&document.querySelector('.p-datatable-table-container').scrollLeft===0})()`),expected:true})
      await click('button[title="View order details"]'); await wait(`!!document.querySelector('[data-pc-name="drawer"]')`); await capture('admin-order-detail', width)
      await key('Escape'); await wait(`!document.querySelector('[data-pc-name="drawer"]')`)
      await go(admin + '/admin/products', '.p-datatable-tbody'); await capture('admin-products', width)
      if(phase==='after') report.interactions.push({name:'products initial edit visible '+width, actual:await evaluate(`(()=>{const r=document.querySelector('button[title=Edit]').getBoundingClientRect();return r.x>=0&&r.right<=innerWidth&&document.querySelector('.p-datatable-table-container').scrollLeft===0})()`),expected:true})
      await clickText('Add Product'); await wait(`!!document.querySelector('[data-pc-name="drawer"]')`); await capture('admin-product-drawer', width)
      await key('Escape'); await wait(`!document.querySelector('[data-pc-name="drawer"]')`)
      process.stdout.write(`${phase}: captured width ${width}\n`)
    }
    await resize(390); state.apiError = true; await go(customer + '/', '.home__error'); await capture('customer-error', 390); state.apiError = false
    await resize(1440); await go(customer + '/', '.product-card')
    await evaluate(`document.querySelector('.product-card__add-btn').focus()`)
    report.interactions.push({ name: 'card-focused-cta', opacity: await evaluate(`getComputedStyle(document.querySelector('.product-card__overlay')).opacity`) })
    const cartPosts = () => requests.filter(r => r.method === 'POST' && r.path === '/api/v1/cart/items').length
    const before = cartPosts(); await key('Enter'); await pause(150)
    report.interactions.push({ name: 'card-native-enter', path: await evaluate('location.pathname'), cartPosts: cartPosts() - before })
    if (phase === 'after') {
      const assert = (name, actual, expected) => {
        const pass = require('node:util').isDeepStrictEqual(actual, expected)
        report.interactions.push({ name, actual, expected, pass })
        if (!pass) throw Error(name + ': ' + JSON.stringify(actual) + ' expected ' + JSON.stringify(expected))
      }
      const active = () => evaluate(`({tag:document.activeElement?.tagName,id:document.activeElement?.id,label:document.activeElement?.getAttribute('aria-label'),class:document.activeElement?.className})`)
      const focus = selector => call(selector => document.querySelector(selector).focus(), selector)
      await resize(390); await go(customer + '/', '.product-card'); await settle()
      await focus('.product-card__link'); await key('Tab')
      assert('card Tab reaches separate cart button', (await active()).class.includes('product-card__add-btn'), true)
      for (const activation of ['Enter', 'Space']) {
        const count = cartPosts(); await key(activation)
        await wait(`!document.querySelector('.product-card__add-btn').disabled`)
        assert('cart ' + activation + ' submits once', cartPosts() - count, 1)
        assert('cart ' + activation + ' keeps route', await evaluate('location.pathname'), '/')
      }
      // CDP touch input triggers the real browser's touch -> click path.
      await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 })
      const point = await evaluate(`(()=>{const e=document.querySelector('.product-card__add-btn');e.scrollIntoView({block:'center'});const b=e.getBoundingClientRect();return {x:b.x+b.width/2,y:b.y+b.height/2}})()`)
      const count = cartPosts()
      await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...point, radiusX: 4, radiusY: 4, force: 1, id: 1 }] })
      await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
      await wait(`!document.querySelector('.product-card__add-btn').disabled`)
      assert('touch cart action submits once', cartPosts() - count, 1)
      assert('touch action keeps route', await evaluate('location.pathname'), '/')
      await send('Emulation.setTouchEmulationEnabled', { enabled: false })
      await focus('.product-card__link'); await key('Enter'); await wait(`location.pathname==='/products/1'`)
      assert('link Enter navigates without adding', cartPosts() - count, 1)
      await go(customer + '/search?keyword=wireless', '.product-card')
      const toggle = '.filter-sidebar button[aria-expanded]'
      assert('mobile filters start closed', await call(selector => document.querySelector(selector).getAttribute('aria-expanded'), toggle), 'false')
      await focus(toggle); await key('Space')
      assert('filter Space opens disclosure', await call(selector => document.querySelector(selector).getAttribute('aria-expanded'), toggle), 'true')
      await focus('[aria-label="Minimum price"]'); await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39 }); await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39 })
      await wait(`document.querySelector('[aria-label="Minimum price"]').getAttribute('aria-valuenow')==='10'`)
      await wait(`!!document.querySelector('.filter-sidebar .p-checkbox-box')`); await click('.filter-sidebar__label')
      await click('.filter-sidebar .p-rating-option:nth-child(4)')
      await wait(`document.querySelectorAll('.search-results__remove-filter').length===3`)
      await capture('customer-search-open-filters', 390)
      await focus(toggle); await key('Enter')
      const tagLabels = await evaluate(`[...document.querySelectorAll('.search-results__remove-filter')].map(e=>e.getAttribute('aria-label'))`)
      for (const label of tagLabels) { await click(`button[aria-label=${JSON.stringify(label)}]`); await waitFor(label => ![...document.querySelectorAll('button[aria-label]')].some(button => button.getAttribute('aria-label') === label), [label], 'removed filter tag') }
      await focus(toggle); await key('Space'); await wait(`!!document.querySelector('.filter-sidebar__label')`); await click('.filter-sidebar__label')
      await wait(`document.querySelectorAll('.search-results__remove-filter').length===1`)
      const searchRequests = requests.filter(r=>r.method==='GET'&&r.path.endsWith('/products/search'))
      const params = Object.fromEntries(new URLSearchParams(searchRequests[searchRequests.length-1].query))
      assert('closed removal then reopen cannot revive price/rating', params, {keyword:'wireless',brand:'Fixture Brand',page:'0',size:'20'})
      assert('brand focus survives response refresh',await evaluate(`document.activeElement.matches('.filter-sidebar input[type=checkbox]')`),true)
      await resize(1024); await settle(); await focus('[aria-label="Minimum price"]')
      assert('resize precondition focuses visible desktop slider',(await active()).label,'Minimum price')
      await resize(390); await settle()
      assert('filter resize returns focus from hidden input',(await active()).label,'Expand filters')
      assert('resize preserves one filter sidebar', await evaluate(`document.querySelectorAll('.filter-sidebar').length`), 1)
      assert('resize retains cleared price/rating controls', await evaluate(`[...document.querySelectorAll('.filter-sidebar [role=slider]')].map(e=>e.getAttribute('aria-valuenow'))`), ['0','1000'])

      await go(admin + '/admin/orders', '.p-datatable-tbody')
      const navToggle = 'button[aria-label="Open navigation"]'
      await focus(navToggle); await key('Enter'); await wait(`!!document.querySelector('#admin-mobile-navigation')`); await settle()
      assert('navigation dialog has name', await evaluate(`document.querySelector('#admin-mobile-navigation').getAttribute('aria-label')`), 'Administration navigation')
      await capture('admin-mobile-navigation',390)
      await key('Tab',true)
      assert('navigation Shift Tab stays in dialog', await evaluate(`document.querySelector('#admin-mobile-navigation').contains(document.activeElement)`), true)
      await key('Tab')
      assert('navigation Tab stays in dialog', await evaluate(`document.querySelector('#admin-mobile-navigation').contains(document.activeElement)`), true)
      await key('Escape'); await wait(`!document.querySelector('#admin-mobile-navigation')`); await settle()
      assert('navigation Escape returns toggle focus', (await active()).label, 'Open navigation')
      await focus(navToggle); await key('Space'); await settle()
      await focus('#admin-mobile-navigation a[href="/admin/products"]'); await key('Enter')
      await wait(`location.pathname==='/admin/products'&&!document.querySelector('#admin-mobile-navigation')`); await settle()
      assert('navigation route closes and focuses main', (await active()).id, 'admin-main-content')
      await focus(navToggle); await key('Enter'); await settle(); await resize(1024); await wait(`!document.querySelector('#admin-mobile-navigation')`); await settle()
      assert('resize closes modal and focuses existing main', (await active()).id, 'admin-main-content')
      assert('desktop has one navigation', await evaluate(`document.querySelectorAll('nav[aria-label="Administration"]').length`),1)
      await click('button[aria-label="Collapse sidebar"]')
      assert('collapsed links retain names', await evaluate(`[...document.querySelectorAll('.admin-sidebar nav a')].every(e=>!!e.getAttribute('aria-label'))`),true)
      await capture('admin-collapsed-navigation',1024)
      await resize(390); await settle()
      await clickText('Add Product'); await wait(`!!document.querySelector('[role=dialog]')`); await settle()
      assert('detail drawer within viewport', await evaluate(`(()=>{const r=document.querySelector('[role=dialog]').getBoundingClientRect();return r.x>=-1&&r.right<=innerWidth+1})()`),true)
      await key('Tab',true); assert('detail drawer traps backwards focus',await evaluate(`document.querySelector('[role=dialog]').contains(document.activeElement)`),true)
      await key('Escape'); await wait(`!document.querySelector('[role=dialog]')`); await settle()
      assert('detail drawer returns Add Product focus',(await active()).label,'Add Product')
      state.apiError=true; await go(admin+'/admin/orders','.orders-page [role=alert]'); await capture('admin-orders-error',390)
      await go(admin+'/admin/products','.products-page [role=alert]'); await capture('admin-products-error',390); state.apiError=false

      await checkout(390)
      await clickText('Continue to Confirm →'); await wait(`!!document.querySelector('.sp .form-error-summary')`); await settle()
      assert('payment error summary receives focus',(await active()).class,'form-error-summary')
      assert('all invalid payment fields describe real errors',await evaluate(`[...document.querySelectorAll('.sp input')].every(e=>e.getAttribute('aria-invalid')==='true'&&!!document.getElementById(e.getAttribute('aria-describedby')))`),true)
      await capture('checkout-payment-errors',390)
      await click('.sp .form-error-summary a')
      assert('summary link focuses invalid field',(await active()).id,'cardholderName')
      for(const [field,value] of Object.entries({cardholderName:'Fixture Customer',cardNumber:'4242424242424242',expiry:'1230',cvv:'123'})) await fill('#'+field,value)
      await wait(`document.querySelectorAll('.sp .field-error').length===0`); await settle()
      await clickText('Continue to Confirm →'); await wait(`!!document.querySelector('.sco__place-order') && document.querySelector('.sco__place-order').getBoundingClientRect().height>0`); await settle()
      assert('successful step focuses heading',(await active()).tag,'H2')
      await capture('checkout-confirm',390)
      state.holdOrder=true; await click('.sco__place-order'); await wait(`!!document.querySelector('[role=dialog]')`); await settle()
      assert('processing copy avoids simulated stages',await evaluate(`document.querySelector('[role=dialog]').innerText.includes('Submitting your order')&&!document.querySelector('[role=dialog]').innerText.includes('Reserving')`),true)
      await key('Tab'); await key('Tab',true)
      assert('processing traps keyboard focus',await evaluate(`document.querySelector('[role=dialog]').contains(document.activeElement)`),true)
      await key('Escape'); assert('processing stays until response',await evaluate(`!!document.querySelector('[role=dialog]')`),true)
      await capture('checkout-processing',390)
      for(const pending of held.splice(0)) await fulfill(pending.requestId,{error:{code:'ORDER_PAYMENT_FAILED',message:'Fixture rejection'}},422,pending.sessionId)
      await wait(`!!document.querySelector('.sp__error-banner')&&!document.querySelector('[role=dialog]')`); await settle()
      assert('payment rejection focuses visible alert',(await active()).class,'sp__error-banner')
      assert('payment rejection keeps an honest next step',await evaluate(`document.querySelector('.sp__error-banner').innerText.includes('Check your orders')`),true)
      await capture('checkout-payment-rejected',390)
      await clickText('Continue to Confirm →'); await wait(`document.querySelector('.sco__place-order').getBoundingClientRect().height>0`)
      await click('.sco__place-order'); await wait(`!!document.querySelector('[role=dialog]')`); await settle()
      state.orderStatus='PAYMENT_PENDING'
      for(const pending of held.splice(0)) await fulfill(pending.requestId,{data:{...summary,status:'PAYMENT_PENDING'},traceId:'uiux-fixture'},200,pending.sessionId)
      state.holdOrder=false
      await wait(`location.pathname.startsWith('/order-confirmation/')&&!!document.querySelector('.ocv__payment-pending')`); await settle()
      assert('pending result focuses its heading',(await active()).tag,'H1')
      assert('pending result does not promise confirmation',await evaluate(`!document.querySelector('.ocv').innerText.includes('Order Confirmed!')&&!document.querySelector('.ocv').innerText.includes('Estimated delivery')`),true)
      assert('card and CVV never enter web storage',await evaluate(`!JSON.stringify({...localStorage,...sessionStorage}).includes('4242424242424242')&&!JSON.stringify({...localStorage,...sessionStorage}).includes('"cvv"')`),true)
      await capture('checkout-pending',390)
      // 200% reflow equivalent: halve a 1440x950 CSS-pixel viewport to 720x475 at DPR 1.
      // This is viewport reflow, not a claim of OS/browser text-only zoom testing.
      await resize(720,475); await go(customer+'/', '.product-card'); await capture('reflow-equivalent-customer',720)
      assert('720 CSS pixels customer has no document overflow',(await metrics()).overflow,0)
      await go(admin+'/admin/orders','.p-datatable-tbody'); await capture('reflow-equivalent-admin',720)
      assert('720 CSS pixels admin has no document overflow',(await metrics()).overflow,0)
    }

  } finally {
    report.finalDiagnostics = await evaluate(`({url:location.href,text:document.body.innerText.slice(-3000),active:document.activeElement?.outerHTML,shipping:[...document.querySelectorAll('.ssa input')].map(e=>({id:e.id,value:e.value,invalid:e.getAttribute('aria-invalid')}))})`).catch(e=>e.message)
    report.requests = requests
    fs.writeFileSync(path.join(outputDir, `${outputPrefix}-browser-results.json`), JSON.stringify(report, null, 2) + '\n')
    socket.terminate()
  }
  console.log(`${phase}: ${report.screens.length} screen/width observations, ${report.interactions.length} interaction observations`)
}
run().catch(error => { console.error(error); process.exitCode = 1 }).finally(async () => { browser.kill(); await pause(500); fs.rmSync(profile, { recursive: true, force: true, maxRetries: 8, retryDelay: 100 }); process.exit(process.exitCode || 0) })
