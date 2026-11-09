import { test, expect, API, signIn } from './fixtures'

test('retries a committed checkout after response loss with the same key, including after reload', async ({
  page,
  shop,
}) => {
  await shop.login('/checkout')
  await shop.fillCheckout()
  const keys: string[] = []
  let committedOrder = 0
  await page.route(`${API}/api/v1/orders`, async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    keys.push(route.request().headers()['idempotency-key'] ?? '')
    const response = await route.fetch()
    expect(response.status()).toBe(201)
    const id = (await response.json()).data.id as number
    if (!committedOrder) {
      committedOrder = id
      // The real backend committed and charged; only the browser loses its response.
      await route.abort('timedout')
    } else {
      expect(id).toBe(committedOrder)
      await route.fulfill({ response })
    }
  })
  await page.getByRole('button', { name: 'Place Order', exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: 'We could not confirm' })).toBeVisible()
  await shop.assertSingleOrder(committedOrder)
  await page.reload()
  await expect(page).toHaveURL('http://localhost:5173/checkout')
  await expect(page.getByRole('button', { name: 'Check Order', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Check Order', exact: true }).click()
  await expect(page).toHaveURL(`http://localhost:5173/order-confirmation/${committedOrder}`)
  await expect(page.getByRole('heading', { name: 'Order Confirmed!' })).toBeVisible()
  expect(keys).toHaveLength(2)
  expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
  expect(keys[1]).toBe(keys[0])
  await shop.assertSingleOrder(committedOrder)
  expect((await (await shop.api.get('/api/v1/cart')).json()).data.items).toEqual([])
})

test('consumes the server cart once and retains units added from another tab', async ({
  page,
  context,
  shop,
}) => {
  await shop.login('/checkout')
  await shop.fillCheckout()
  const secondTab = await context.newPage()
  // sessionStorage is per tab; restore this tab through the real Keycloak SSO session.
  await secondTab.goto('/orders')
  await expect(secondTab).toHaveURL('http://localhost:5173/orders')
  await secondTab.goto(`/products/${shop.product.id}`)
  await expect(secondTab.getByRole('button', { name: 'Add to Cart', exact: true })).toBeVisible()
  await page.route(`${API}/api/v1/orders`, async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    const response = await route.fetch()
    expect(response.status()).toBe(201)
    // Add after the submitted snapshot is frozen but before cart acknowledgement.
    await secondTab.getByRole('button', { name: 'Add to Cart', exact: true }).click()
    await expect
      .poll(async () => (await (await shop.api.get('/api/v1/cart')).json()).data.totalItems)
      .toBe(3)
    await route.fulfill({ response })
  })
  let receipt: unknown
  await page.route(`${API}/api/v1/cart/checkout`, async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    receipt = route.request().postDataJSON()
    await route.continue()
  })
  await page.getByRole('button', { name: 'Place Order', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Order Confirmed!' })).toBeVisible()
  await page.goto('/cart')
  await expect(page.getByRole('heading', { name: 'Shopping Cart' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('link', { name: 'Shopping cart, 1 items' })).toBeVisible()
  const remaining = (await (await shop.api.get('/api/v1/cart')).json()).data
  expect(remaining.items).toHaveLength(1)
  expect(remaining.items[0]).toMatchObject({ productId: shop.product.id, quantity: 1 })
  expect((await shop.api.post('/api/v1/cart/checkout', { data: receipt })).ok()).toBeTruthy()
  expect((await (await shop.api.get('/api/v1/cart')).json()).data.totalItems).toBe(1)
})

test('keeps the accepted order when cart cleanup fails and finishes cleanup after reload', async ({
  page,
  shop,
}) => {
  await shop.login('/checkout')
  await shop.fillCheckout()
  let failCleanup = true
  await page.route(`${API}/api/v1/cart/checkout`, async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    if (failCleanup)
      await route.fulfill({ status: 503, contentType: 'application/json', body: '{}' })
    else await route.continue()
  })
  await page.getByRole('button', { name: 'Place Order', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Order Confirmed!' })).toBeVisible()
  await expect(
    page.getByText('Your order was received. Your cart still needs to be refreshed.'),
  ).toBeVisible()
  const orderId = Number(new URL(page.url()).pathname.split('/').pop())
  await shop.assertSingleOrder(orderId)
  expect((await (await shop.api.get('/api/v1/cart')).json()).data.totalItems).toBe(2)
  failCleanup = false
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Order Confirmed!' })).toBeVisible()
  await expect
    .poll(async () => (await (await shop.api.get('/api/v1/cart')).json()).data.totalItems)
    .toBe(0)
  await page.goto('/cart')
  await page.reload()
  await expect(page.getByText('Your cart is empty', { exact: true })).toBeVisible()
  await shop.assertSingleOrder(orderId)
})

test('restores a protected order deep link on reload and after a fresh OIDC login', async ({
  page,
  browser,
  shop,
}) => {
  await shop.login('/checkout')
  await shop.fillCheckout()
  await page.getByRole('button', { name: 'Place Order', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Order Confirmed!' })).toBeVisible()
  const orderId = Number(new URL(page.url()).pathname.split('/').pop())
  const path = `/orders/${orderId}?source=browser-test#details`
  await page.goto(path)
  await expect(page.getByRole('heading', { name: `Order #${orderId}`, exact: true })).toBeVisible()
  await page.reload()
  await expect(page).toHaveURL(`http://localhost:5173${path}`)
  await expect(page.getByRole('heading', { name: `Order #${orderId}`, exact: true })).toBeVisible()
  // A fresh context has neither an in-memory user, sessionStorage nor Keycloak cookies.
  const fresh = await browser.newContext()
  try {
    const directLink = await fresh.newPage()
    await directLink.goto(`http://localhost:5173${path}`)
    await signIn(directLink, shop.username)
    await expect(directLink).toHaveURL(`http://localhost:5173${path}`)
    await expect(
      directLink.getByRole('heading', { name: `Order #${orderId}`, exact: true }),
    ).toBeVisible()
  } finally {
    await fresh.close()
  }
})

test('recovers a lost cart acknowledgement after Redis committed without blocking checkout', async ({
  page,
  shop,
}) => {
  await shop.login('/checkout')
  await shop.fillCheckout()
  let acknowledgements = 0
  await page.route(`${API}/api/v1/cart/checkout`, async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    acknowledgements++
    if (acknowledgements === 1) {
      const committed = await route.fetch()
      expect(committed.status()).toBe(200)
      expect((await committed.json()).data.items).toEqual([])
      await route.abort('timedout')
    } else {
      await route.continue()
    }
  })
  await page.getByRole('button', { name: 'Place Order', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Order Confirmed!' })).toBeVisible()
  await expect.poll(() => acknowledgements).toBe(2)
  const orderId = Number(new URL(page.url()).pathname.split('/').pop())
  await shop.assertSingleOrder(orderId)
  await page.goto('/cart')
  await page.reload()
  await expect(page.getByText('Your cart is empty', { exact: true })).toBeVisible()
  await expect(page.getByRole('alertdialog', { name: 'Maintenance notice' })).toHaveCount(0)
})
