import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { test, expect } from './fixtures'

function service(action: 'saga-stop' | 'saga-start' | 'payment-stop' | 'payment-start') {
  execFileSync('bash', [resolve(import.meta.dirname, '../../../infra/e2e/stack.sh'), action], {
    timeout: 45000,
    stdio: 'pipe',
  })
}

test('retains pending checkout across reload and cancels before a stopped Saga can dispatch', async ({
  page,
  shop,
}) => {
  await shop.login('/checkout')
  await shop.fillCheckout()
  service('saga-stop')
  let orderId = 0
  try {
    await page.getByRole('button', { name: 'Place Order', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'Order Received!' })).toBeVisible()
    orderId = Number(new URL(page.url()).pathname.split('/').pop())
    expect((await (await shop.api.get('/api/v1/cart')).json()).data.totalItems).toBe(2)
    await page.reload()
    await expect(page.getByRole('heading', { name: 'Order Received!' })).toBeVisible()
    await expect(page.getByText('Estimated delivery: 3–5 business days')).toHaveCount(0)
    await page.getByRole('button', { name: 'Track Order', exact: true }).click()
    await page.getByRole('button', { name: 'Cancel Order', exact: true }).click()
    await page
      .getByRole('alertdialog')
      .getByRole('button', { name: 'Cancel Order', exact: true })
      .click()
    await expect(page.getByText('Cancellation in progress').first()).toBeVisible()
    expect((await (await shop.api.get(`/api/v1/orders/${orderId}`)).json()).data.status).toBe(
      'CANCELLATION_PENDING',
    )
    expect((await (await shop.api.get('/api/v1/cart')).json()).data.totalItems).toBe(2)
  } finally {
    service('saga-start')
  }
  await expect
    .poll(
      async () => (await (await shop.api.get(`/api/v1/orders/${orderId}`)).json()).data.status,
      { timeout: 60000 },
    )
    .toBe('CANCELLED')
  await page.reload()
  await expect(page.getByText('Cancelled', { exact: true }).first()).toBeVisible()
  expect((await (await shop.api.get('/api/v1/cart')).json()).data.totalItems).toBe(2)
})

test('shows cancellation pending while payment is down and polls through confirmed refund', async ({
  page,
  shop,
}) => {
  await shop.login('/checkout')
  await shop.fillCheckout()
  await page.getByRole('button', { name: 'Place Order', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Order Confirmed!' })).toBeVisible()
  const orderId = Number(new URL(page.url()).pathname.split('/').pop())
  service('payment-stop')
  try {
    await page.getByRole('button', { name: 'Track Order', exact: true }).click()
    await page.getByRole('button', { name: 'Cancel Order', exact: true }).click()
    await page
      .getByRole('alertdialog')
      .getByRole('button', { name: 'Cancel Order', exact: true })
      .click()
    await expect(page.getByText('Cancellation in progress').first()).toBeVisible()
    await page.reload()
    await expect(page.getByText('Cancellation in progress').first()).toBeVisible()
    expect((await (await shop.api.get(`/api/v1/orders/${orderId}`)).json()).data.status).toBe(
      'CANCELLATION_PENDING',
    )
  } finally {
    service('payment-start')
  }
  await expect(page.getByText('Cancelled', { exact: true }).first()).toBeVisible({ timeout: 60000 })
})
