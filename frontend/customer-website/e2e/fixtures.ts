import { randomUUID } from 'node:crypto'
import { test as base, expect, type APIRequestContext, type Page } from '@playwright/test'

export const API = 'http://127.0.0.1:18080'
export const KEYCLOAK = 'http://localhost:18180'
const PASSWORD = 'BrowserFixture123!'

type Shop = {
  api: APIRequestContext
  product: { id: number; name: string; price: number }
  username: string
  login: (path: string) => Promise<void>
  fillCheckout: () => Promise<void>
  assertSingleOrder: (id: number) => Promise<void>
}

async function token(
  request: APIRequestContext,
  username: string,
  password: string,
  realm = 'robomart',
  client = 'robo-mart-frontend',
) {
  const response = await request.post(`${KEYCLOAK}/realms/${realm}/protocol/openid-connect/token`, {
    form: { grant_type: 'password', client_id: client, username, password },
  })
  expect(response.ok(), `Fixture token request failed (${response.status()})`).toBeTruthy()
  return (await response.json()).access_token as string
}

export async function signIn(page: Page, username: string) {
  await expect(page).toHaveURL(/localhost:18180\/realms\/robomart\//)
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(PASSWORD)
  await page.locator('#kc-login').click()
}

export const test = base.extend<{ shop: Shop }>({
  shop: async ({ playwright, page }, use) => {
    const request = await playwright.request.newContext()
    // Fail before any writes unless the fixed loopback endpoints are the isolated realm fixture.
    const discovery = await request.get(
      `${KEYCLOAK}/realms/robomart/.well-known/openid-configuration`,
    )
    expect(discovery.ok(), 'Start infra/e2e/stack.sh up before browser tests').toBeTruthy()
    expect((await discovery.json()).issuer).toBe(`${KEYCLOAK}/realms/robomart`)
    const adminToken = await token(request, 'admin', 'admin_e2e_only', 'master', 'admin-cli')
    const realmAdmin = await playwright.request.newContext({
      baseURL: `${KEYCLOAK}/admin/realms/robomart/`,
      extraHTTPHeaders: { Authorization: `Bearer ${adminToken}` },
    })
    const name = `browser-${randomUUID()}`
    const createdUser = await realmAdmin.post('users', {
      data: {
        username: name,
        firstName: 'Browser',
        lastName: 'Fixture',
        email: `${name}@example.test`,
        enabled: true,
        emailVerified: true,
        credentials: [{ type: 'password', value: PASSWORD, temporary: false }],
      },
    })
    expect(createdUser.status()).toBe(201)
    const userId = createdUser.headers().location!.split('/').pop()!
    let productId: number | undefined
    let api: APIRequestContext | undefined
    let admin: APIRequestContext | undefined
    try {
      const customerRole = await realmAdmin.get('roles/CUSTOMER')
      expect(customerRole.ok()).toBeTruthy()
      expect(
        (
          await realmAdmin.post(`users/${userId}/role-mappings/realm`, {
            data: [await customerRole.json()],
          })
        ).ok(),
      ).toBeTruthy()
      api = await playwright.request.newContext({
        baseURL: API,
        extraHTTPHeaders: {
          Authorization: `Bearer ${await token(request, name, PASSWORD)}`,
        },
      })
      admin = await playwright.request.newContext({
        baseURL: API,
        extraHTTPHeaders: {
          Authorization: `Bearer ${await token(request, 'demo-admin', 'admin123')}`,
        },
      })
      const createdProduct = await admin.post('/api/v1/admin/products', {
        data: {
          name,
          sku: name,
          categoryId: 1,
          price: '19.95',
          brand: 'Browser E2E',
        },
      })
      expect(createdProduct.status()).toBe(201)
      productId = (await createdProduct.json()).data.id as number
      const id = productId
      await expect
        .poll(
          async () => {
            const restock = await admin!.put(`/api/v1/admin/inventory/${id}/restock`, {
              data: {
                quantity: 10,
                reason: 'Isolated browser fixture',
              },
            })
            // A 404 means the asynchronous zero-stock bootstrap has not arrived yet.
            expect([200, 404]).toContain(restock.status())
            return restock.status()
          },
          { timeout: 45_000, intervals: [1000] },
        )
        .toBe(200)
      await expect
        .poll(
          async () => (await (await api!.get(`/api/v1/products/${id}`)).json()).data.stockQuantity,
          { timeout: 45_000, intervals: [1000] },
        )
        .toBe(10)
      expect(
        (await api.post('/api/v1/cart/items', { data: { productId: id, quantity: 2 } })).status(),
      ).toBe(201)
      const shop: Shop = {
        api,
        product: { id, name, price: 19.95 },
        username: name,
        async login(path) {
          await page.goto(path)
          await signIn(page, name)
          await expect(page).toHaveURL(`http://localhost:5173${path}`)
        },
        async fillCheckout() {
          await page.getByRole('button', { name: 'Continue to Shipping' }).click()
          await page.getByLabel('Full Name', { exact: true }).fill('Browser Customer')
          await page.getByLabel('Street Address').fill('123 Fixture Street')
          await page.getByLabel('City', { exact: true }).fill('Austin')
          await page.getByLabel('State / Province').fill('TX')
          await page.getByLabel('Postal Code').fill('75001')
          await page.getByLabel('Country', { exact: true }).fill('US')
          await page.getByRole('button', { name: 'Continue to Payment' }).click()
          await page.getByLabel('Cardholder Name').fill('Browser Customer')
          await page.getByLabel('Card Number', { exact: true }).fill('4242424242424242')
          await page.getByLabel('Expiry Date').fill('12/99')
          await page.getByLabel('CVV').fill('123')
          await page.getByRole('button', { name: 'Continue to Confirm' }).click()
          await expect(page.getByRole('button', { name: 'Place Order', exact: true })).toBeVisible()
        },
        async assertSingleOrder(orderId) {
          const response = await api!.get('/api/v1/orders')
          expect(response.ok()).toBeTruthy()
          const orders = await response.json()
          expect(orders.pagination.totalElements).toBe(1)
          expect(orders.data[0]).toMatchObject({
            id: orderId,
            status: 'CONFIRMED',
            totalAmount: 39.9,
          })
          const payment = await admin!.get(`/api/v1/admin/payments/order/${orderId}`)
          expect(payment.ok()).toBeTruthy()
          expect((await payment.json()).data).toMatchObject({
            orderId: String(orderId),
            amount: 39.9,
            status: 'COMPLETED',
          })
        },
      }
      await use(shop)
    } finally {
      // Every test owns a fresh customer and product; preserve all pre-existing fixture data.
      if (api) {
        const orders = await api.get('/api/v1/orders')
        if (orders.ok()) {
          for (const order of (await orders.json()).data) {
            if (['PENDING', 'CONFIRMED'].includes(order.status)) {
              expect(
                (
                  await api.post(`/api/v1/orders/${order.id}/cancel`, {
                    data: { reason: 'Browser fixture cleanup' },
                  })
                ).ok(),
              ).toBeTruthy()
            }
          }
        }
        if (productId) {
          const removed = await api.delete(`/api/v1/cart/items/${productId}`)
          expect([204, 404]).toContain(removed.status())
        }
        await api.dispose()
      }
      if (productId && admin)
        expect((await admin.delete(`/api/v1/admin/products/${productId}`)).ok()).toBeTruthy()
      expect((await realmAdmin.delete(`users/${userId}`)).ok()).toBeTruthy()
      await admin?.dispose()
      await realmAdmin.dispose()
      await request.dispose()
    }
  },
})

export { expect }
