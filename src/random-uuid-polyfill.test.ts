import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { it } from 'node:test'
import { loadMobileModule } from '../tooling/load-mobile-module.ts'
import { withoutRandomUUID } from '../tooling/without-random-uuid.ts'

// The helper itself is covered by the shared src/bridge/uuid.test.ts; this is
// the Android boot wiring around it.

it('installs crypto.randomUUID at boot on a WebView that lacks it', async () => {
  await withoutRandomUUID(async () => {
    await loadMobileModule('./src/random-uuid-polyfill.ts')
    assert.match(crypto.randomUUID(), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })
})

it('boots the polyfill before anything else, and the core loads only after it', () => {
  const bootstrap = readFileSync(new URL('./bootstrap.ts', import.meta.url), 'utf8')
  assert.equal(bootstrap.match(/^import\s.*$/m)?.[0], "import './random-uuid-polyfill'")
  assert.doesNotMatch(bootstrap, /^import .*@zennotes\//m)
})
