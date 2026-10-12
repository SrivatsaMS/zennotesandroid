import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { it } from 'node:test'
import { registerPlugin } from '@capacitor/core'
import { loadMobileModule } from '../../tooling/load-mobile-module.ts'

const account = {
  base_url: 'https://zennotes.org', connected_at: '2026-09-14T12:00:00Z',
  user: { name: 'Test', email: 'test@example.test' },
  device: { id: 'test-device', name: 'Test phone', platform: 'android' }
}
const credential = JSON.stringify({ base_url: account.base_url, token: 'test-only', account })

async function coldLaunch(saved: Map<string, string>, http: Record<string, unknown> = {}) {
  const lifecycle: string[] = []
  // Entries a restored install can no longer decrypt: its Keystore key did not come back.
  const unreadable = new Set<string>()
  const listeners = new Map<string, (event: any) => void>()
  let lifetime = new AbortController()
  let delayRead: { entered(): void; wait: Promise<void> } | null = null
  // Exercise the real Capacitor lazy proxy: concurrent first calls can
  // instantiate separate implementations, each with its own key prefix.
  const secureStorage = registerPlugin(`TestCloudStorage${randomUUID()}`, {
    web: async () => {
      await Promise.resolve()
      return new class {
        prefix = 'capacitor-storage_'
        async setKeyPrefix(prefix: string) { this.prefix = prefix }
        async setSynchronize(_value: boolean) {}
        async setDefaultKeychainAccess(_value: unknown) {}
        async getItem(key: string) {
          if (unreadable.has(this.prefix + key)) throw Object.assign(new Error('Could not decrypt the item'), { code: 'osError' })
          const value = saved.get(this.prefix + key) ?? null
          if (key === 'credential' && delayRead) {
            const delayed = delayRead
            delayRead = null
            delayed.entered()
            await delayed.wait
          }
          return value
        }
        async setItem(key: string, value: string) { saved.set(this.prefix + key, value) }
        async removeItem(key: string) { saved.delete(this.prefix + key) }
        async keys() {
          return [...saved.keys()].filter((key) => key.startsWith(this.prefix)).map((key) => key.slice(this.prefix.length))
        }
      }()
    }
  })
  const api = await loadMobileModule('./src/bridge/mobile-cloud-auth.ts', {
    '@capacitor/core': { Capacitor: { isNativePlatform: () => true }, CapacitorHttp: http },
    '@capacitor/app': { App: { addListener: async (name: string, listener: (event: any) => void) => {
      listeners.set(name, listener)
      return {}
    }, getLaunchUrl: async () => null } },
    '@aparajita/capacitor-secure-storage': {
      SecureStorage: secureStorage,
      KeychainAccess: { whenUnlockedThisDeviceOnly: 'device-only' }
    },
    './cloud-sync-client': {
      createCloudSyncClient: () => assert.fail('status must only read storage'),
      stopMobileCloudRequests: () => { lifecycle.push('stop'); lifetime.abort() },
      resumeMobileCloudRequests: () => { lifecycle.push('resume'); if (lifetime.signal.aborted) lifetime = new AbortController() },
      mobileCloudRequestSignal: () => lifetime.signal
    }
  })
  await api.configureMobileCloudAuth('test-version')
  api.lifecycle = lifecycle
  api.appState = (isActive: boolean) => listeners.get('appStateChange')!({ isActive })
  api.openUrl = (url: string) => listeners.get('appUrlOpen')!({ url })
  api.unreadable = unreadable
  api.pauseNextCredentialRead = () => {
    let entered!: () => void
    let release!: () => void
    const started = new Promise<void>(resolve => { entered = resolve })
    const wait = new Promise<void>(resolve => { release = resolve })
    delayRead = { entered, wait }
    return { started, release }
  }
  return api
}

for (const prefix of ['zennotes.cloud.', 'capacitor-storage_']) {
  it(`loads a saved account from ${prefix} on every cold launch`, async () => {
    const saved = new Map([[prefix + 'credential', credential]])
    for (let launch = 0; launch < 2; launch++) {
      const api = await coldLaunch(saved)
      const statuses = await Promise.all([api.getMobileCloudAccountStatus(), api.getMobileCloudAccountStatus()])
      assert.deepEqual(statuses, [{ state: 'connected', account }, { state: 'connected', account }])
      assert.deepEqual([...saved.keys()], ['zennotes.cloud.credential'])
    }
  })
}

it('preserves the canonical account and prevents a legacy credential from returning after logout', async () => {
  const saved = new Map([
    ['zennotes.cloud.credential', credential],
    ['capacitor-storage_credential', JSON.stringify({ base_url: account.base_url, token: 'old-test-token', account })]
  ])
  const api = await coldLaunch(saved)
  assert.equal((await api.authenticatedCredential()).token, 'test-only')
  await api.logoutMobileCloudAccount()
  assert.ok(api.lifecycle.includes('stop'))
  assert.equal(saved.size, 0)
  assert.deepEqual(await (await coldLaunch(saved)).getMobileCloudAccountStatus(), { state: 'disconnected', account: null })
})

it('stops pending Cloud requests in the background and resumes on foreground', async () => {
  const api = await coldLaunch(new Map([['zennotes.cloud.credential', credential]]))
  api.appState(false)
  api.appState(true)
  assert.deepEqual(api.lifecycle, ['stop', 'resume'])
})

it('rejects a credential read spanning logout even after foreground creates a new lifetime', async () => {
  const saved = new Map([['zennotes.cloud.credential', credential]])
  const api = await coldLaunch(saved)
  await api.getMobileCloudAccountStatus()
  const paused = api.pauseNextCredentialRead()
  const pending = api.authenticatedClient().catch((error: unknown) => error)
  await paused.started
  await api.logoutMobileCloudAccount()
  api.appState(true)
  paused.release()
  assert.equal((await pending).name, 'AbortError')
  assert.equal(saved.size, 0)
})

it('rejects invalid recovered credentials through the shared auth validator', async () => {
  const invalid = JSON.stringify({ base_url: 'https://wrong.example.test', token: 'test-only', account })
  const saved = new Map([['capacitor-storage_credential', invalid]])
  const api = await coldLaunch(saved)
  assert.deepEqual(await api.getMobileCloudAccountStatus(), { state: 'disconnected', account: null })
  assert.equal(saved.size, 0)
})

it('recovers pending sign-in state across cold launches', async () => {
  const pending = JSON.stringify({
    base_url: account.base_url, state: 'test-state', code_verifier: 'a'.repeat(43), expires_at: '2099-01-01T00:00:00Z'
  })
  const saved = new Map([['capacitor-storage_pending-auth', pending]])
  for (let launch = 0; launch < 2; launch++) {
    const api = await coldLaunch(saved)
    assert.deepEqual(await api.getMobileCloudAccountStatus(), { state: 'connecting', account: null })
    assert.deepEqual([...saved.entries()], [['zennotes.cloud.pending-auth', pending]])
  }
})

const unlockedKey = { format: 1, key: Buffer.alloc(32, 7).toString('base64'), version: 1 }

it('keeps one encryption key per account, and reads a missing, damaged or unreadable one as locked', async () => {
  const saved = new Map([['zennotes.cloud.credential', credential]])
  const api = await coldLaunch(saved)
  const store = api.mobileCloudAccountKeyStore(account.base_url, 'Test@Example.test')
  assert.equal(await store.load(), null)
  await store.save(unlockedKey)
  assert.deepEqual(await api.mobileCloudAccountKeyStore(`${account.base_url}/`, account.user.email).load(), unlockedKey)
  assert.equal(await api.mobileCloudAccountKeyStore('https://other.example.test', account.user.email).load(), null)
  const [entry] = [...saved.keys()].filter((key) => key.includes('e2ee-account-key.'))
  assert.match(entry!, /^zennotes\.cloud\.e2ee-account-key\.[0-9a-f]{64}$/)

  saved.set(entry!, '{"format":1')
  assert.equal(await store.load(), null)
  saved.set(entry!, JSON.stringify({ ...unlockedKey, key: 'c2hvcnQ=' }))
  assert.equal(await store.load(), null)
  saved.set(entry!, JSON.stringify(unlockedKey))
  api.unreadable.add(entry!)
  assert.equal(await store.load(), null)
  api.unreadable.clear()
  await store.clear()
  assert.equal(saved.has(entry!), false)
})

it('revokes this device and forgets every encryption key when signing out', async () => {
  for (const status of [204, 401, 500]) {
    const saved = new Map([['zennotes.cloud.credential', credential]])
    const requests: unknown[] = []
    const api = await coldLaunch(saved, { request: async (request: unknown) => { requests.push(request); return { status, data: {} } } })
    await api.mobileCloudAccountKeyStore(account.base_url, account.user.email).save(unlockedKey)
    saved.set(`zennotes.cloud.e2ee-account-key.${'f'.repeat(64)}`, JSON.stringify(unlockedKey))

    assert.deepEqual(await api.logoutMobileCloudAccount(), { state: 'disconnected', account: null })
    assert.deepEqual(requests, [{
      method: 'DELETE', url: 'https://zennotes.org/api/v1/devices/current',
      headers: { Accept: 'application/json', Authorization: 'Bearer test-only' },
      connectTimeout: 10_000, readTimeout: 10_000, disableRedirects: true
    }])
    assert.equal(saved.size, 0, `answer ${status}`)
  }
})

it('keeps the encryption key across a new sign-in to the same account, and drops it on a switch', async () => {
  const saved = new Map([['zennotes.cloud.credential', credential]])
  let signedInAs = account.user.email
  const api = await coldLaunch(saved, {
    post: async () => ({ status: 200, data: {
      token: 'next-token', user: { name: 'Test', email: signedInAs },
      device: { id: 'test-device', name: 'Test phone', platform: 'android' }
    } })
  })
  const signIn = async (email: string) => {
    signedInAs = email
    saved.set('zennotes.cloud.pending-auth', JSON.stringify({
      base_url: account.base_url, state: 'test-state', code_verifier: 'a'.repeat(43), expires_at: '2099-01-01T00:00:00Z'
    }))
    const changed = new Promise((resolve) => { const stop = api.onMobileCloudAccountChange((value: unknown) => { stop(); resolve(value) }) })
    api.openUrl('zennotes://auth?code=abc123&state=test-state')
    let timer: ReturnType<typeof setTimeout> | undefined
    await Promise.race([changed, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Sign-in did not finish')), 5_000) })])
    clearTimeout(timer)
  }
  const store = api.mobileCloudAccountKeyStore(account.base_url, account.user.email)
  await store.save(unlockedKey)

  await signIn(account.user.email.toUpperCase())
  assert.deepEqual(await store.load(), unlockedKey)
  assert.equal((await api.authenticatedCredential()).token, 'next-token')

  await signIn('someone-else@example.test')
  assert.equal(await store.load(), null)
  assert.deepEqual([...saved.keys()].filter((key) => key.includes('e2ee-account-key.')), [])
})
