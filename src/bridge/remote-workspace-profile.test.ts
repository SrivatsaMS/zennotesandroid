import assert from 'node:assert/strict'
import { it } from 'node:test'
import { loadMobileModule } from '../../tooling/load-mobile-module.ts'
import { withoutRandomUUID } from '../../tooling/without-random-uuid.ts'

it('saves a new server profile on a WebView without crypto.randomUUID', async () => {
  const stored = new Map<string, string>()
  const { saveProfile, listProfiles } = await loadMobileModule('./src/bridge/remote-workspace.ts', {
    '@capacitor/preferences': {
      Preferences: {
        get: async ({ key }: { key: string }) => ({ value: stored.get(key) ?? null }),
        set: async ({ key, value }: { key: string; value: string }) => { stored.set(key, value) }
      }
    }
  })
  const saved = await withoutRandomUUID(() => saveProfile({ baseUrl: 'https://notes.example.test', authToken: 'test-only' }))
  assert.match(saved.id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  assert.deepEqual((await listProfiles()).map((profile: { id: string }) => profile.id), [saved.id])
})
