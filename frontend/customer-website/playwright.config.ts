import { defineConfig, devices } from '@playwright/test'

// Keep the fixture bundle separate from normal builds and their environment variables.
const fixtureBundle = 'node_modules/.cache/robomart-browser-e2e'

// Target only the disposable Compose fixture, never the demo server.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npm run type-check && npm run build-only -- --outDir ${fixtureBundle} && npm run preview -- --outDir ${fixtureBundle} --host localhost --port 5173 --strictPort`,
    url: 'http://localhost:5173',
    reuseExistingServer: false,
    env: {
      VITE_API_URL: 'http://127.0.0.1:18080',
      VITE_KEYCLOAK_URL: 'http://localhost:18180',
      VITE_KEYCLOAK_CLIENT_ID: 'robo-mart-frontend',
    },
  },
})
