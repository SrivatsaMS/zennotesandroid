import assert from 'node:assert/strict'
import { it } from 'node:test'
import { loadMobileModule } from '../../tooling/load-mobile-module.ts'

const store = new Map<string, string>()
;(globalThis as { localStorage?: unknown }).localStorage = {
  getItem: (key: string) => store.get(key) ?? null,
  setItem: (key: string, value: string) => void store.set(key, value),
  removeItem: (key: string) => void store.delete(key)
}

interface Harness {
  api: Record<string, any>
  calls: string[]
  vaultListeners: Set<() => void>
  /** Sends the native `sync` event, as BackgroundSyncWorker does. */
  job(id: string): void
  /** Emits a vault change, as an in-app write or the sync's rescan does. */
  change(): void
}

async function load(): Promise<Harness> {
  const calls: string[] = []
  const listeners = new Map<string, (event: { id: string }) => void>()
  const vaultListeners = new Set<() => void>()
  const plugin = {
    begin: async () => void calls.push('begin'),
    end: async () => void calls.push('end'),
    schedule: async ({ minutes }: { minutes: number }) => void calls.push(`schedule ${minutes}`),
    cancel: async () => void calls.push('cancel'),
    finished: async ({ id, ok }: { id: string; ok: boolean }) => void calls.push(`finished ${id} ${ok}`),
    addListener: async (event: string, listener: (event: { id: string }) => void) => {
      listeners.set(event, listener)
      return { remove: async () => {} }
    }
  }
  const api = await loadMobileModule('./src/bridge/background-sync.ts', {
    '@capacitor/core': { registerPlugin: () => plugin },
    './cloud-sync-client': {
      stopMobileCloudRequests: () => void calls.push('stop'),
      resumeMobileCloudRequests: () => void calls.push('resume')
    }
  })
  return {
    api,
    calls,
    vaultListeners,
    job: (id) => listeners.get('sync')?.({ id }),
    change: () => {
      for (const listener of vaultListeners) listener()
    }
  }
}

function host(harness: Harness, options: { ready?: boolean; sync?: () => Promise<void> } = {}) {
  return {
    ready: async () => {
      harness.calls.push('ready')
      return options.ready ?? true
    },
    sync: options.sync ?? (async () => void harness.calls.push('sync')),
    onVaultChange: (listener: () => void) => {
      harness.vaultListeners.add(listener)
      return () => void harness.vaultListeners.delete(listener)
    }
  }
}

/** Installs and lets the first schedule update land, then forgets its calls. */
async function installed(harness: Harness, options: Parameters<typeof host>[1] = {}): Promise<void> {
  harness.api.installBackgroundSync(host(harness, options))
  await until(() => harness.calls.some((call) => call.startsWith('schedule') || call === 'cancel'))
  harness.calls.length = 0
}

async function until(done: () => boolean): Promise<void> {
  for (let tries = 0; !done(); tries += 1) {
    assert.ok(tries < 300, 'timed out waiting')
    await new Promise((resolve) => setTimeout(resolve, 5))
  }
}

/** The calls with the schedule update leaving makes taken out. */
function withoutScheduling(calls: string[]): string[] {
  const out = [...calls]
  const scheduleAt = out.findIndex((call) => call.startsWith('schedule') || call === 'cancel')
  if (scheduleAt >= 0) {
    out.splice(scheduleAt, 1)
    // Its readiness check is the first 'ready' (refreshSchedule runs first).
    out.splice(out.indexOf('ready'), 1)
  }
  return out
}

it('is on by default, and turning it on again removes the stored choice', async () => {
  store.clear()
  const { api } = await load()
  assert.equal(api.backgroundSyncEnabled(), true)
  api.setBackgroundSyncEnabled(false)
  assert.equal(store.get(api.BACKGROUND_SYNC_KEY), 'off')
  assert.equal(api.backgroundSyncEnabled(), false)
  api.setBackgroundSyncEnabled(true)
  assert.equal(store.has(api.BACKGROUND_SYNC_KEY), false)
})

it('stops Cloud requests at once on leaving before it is installed, as leaving always did', async () => {
  store.clear()
  const harness = await load()
  harness.api.leaveApp()
  harness.api.returnToApp()
  assert.deepEqual(harness.calls, ['stop', 'resume'])
})

it('schedules the job only while the setting is on and the vault is linked', async () => {
  store.clear()
  const linked = await load()
  linked.api.installBackgroundSync(host(linked))
  await until(() => linked.calls.length >= 2)
  assert.deepEqual(linked.calls, ['ready', `schedule ${linked.api.SYNC_EVERY_MINUTES}`])

  const unlinked = await load()
  unlinked.api.installBackgroundSync(host(unlinked, { ready: false }))
  await until(() => unlinked.calls.length >= 2)
  assert.deepEqual(unlinked.calls, ['ready', 'cancel'])

  store.set('zn:background-sync', 'off')
  const off = await load()
  off.api.installBackgroundSync(host(off))
  await until(() => off.calls.length >= 1)
  assert.deepEqual(off.calls, ['cancel'], 'a phone with the setting off is never woken for it')
})

it('cancels and reschedules the job as the setting changes', async () => {
  store.clear()
  const harness = await load()
  await installed(harness)
  harness.api.setBackgroundSyncEnabled(false)
  await until(() => harness.calls.length >= 1)
  assert.deepEqual(harness.calls, ['cancel'])
  harness.calls.length = 0
  harness.api.setBackgroundSyncEnabled(true)
  await until(() => harness.calls.length >= 2)
  assert.deepEqual(harness.calls, ['ready', 'schedule 15'])
})

it('stops at once when the setting is off', async () => {
  store.clear()
  store.set('zn:background-sync', 'off')
  const harness = await load()
  await installed(harness)
  harness.api.leaveApp()
  await harness.api.backgroundSyncSettled()
  assert.deepEqual(harness.calls, ['stop'])
})

it('finishes a sync on leaving, queuing and then dropping the catch-up run', async () => {
  store.clear()
  const harness = await load()
  await installed(harness)
  harness.api.leaveApp()
  assert.equal(harness.calls.includes('stop'), false, 'requests keep running while the final sync is pending')
  await harness.api.backgroundSyncSettled()
  await until(() => harness.calls.some((call) => call.startsWith('schedule')))
  assert.deepEqual(withoutScheduling(harness.calls), ['ready', 'begin', 'sync', 'end', 'stop'])
})

it('runs a second pass when the vault changed during the first, and never a third', async () => {
  store.clear()
  const harness = await load()
  let passes = 0
  await installed(harness, {
    sync: async () => {
      passes += 1
      harness.change()
    }
  })
  harness.api.leaveApp()
  await harness.api.backgroundSyncSettled()
  assert.equal(passes, 2)
})

it('syncs when the job asks while the app is away, then stops and answers', async () => {
  store.clear()
  const harness = await load()
  await installed(harness)
  harness.api.leaveApp()
  await harness.api.backgroundSyncSettled()
  harness.calls.length = 0
  harness.job('job-1')
  await until(() => harness.calls.some((call) => call.startsWith('finished')))
  assert.deepEqual(harness.calls, ['resume', 'ready', 'sync', 'stop', 'finished job-1 true'])
})

it('leaves requests open when the job asks while the app is on screen', async () => {
  store.clear()
  const harness = await load()
  await installed(harness)
  harness.job('job-2')
  await until(() => harness.calls.some((call) => call.startsWith('finished')))
  assert.deepEqual(harness.calls, ['resume', 'ready', 'sync', 'finished job-2 true'])
})

it('lets the final sync from leaving end before the job runs its own', async () => {
  store.clear()
  const harness = await load()
  let release!: () => void
  const gate = new Promise<void>((resolve) => { release = resolve })
  let first = true
  await installed(harness, {
    sync: async () => {
      harness.calls.push('sync')
      if (first) {
        first = false
        await gate
      }
    }
  })
  harness.api.leaveApp()
  await until(() => harness.calls.includes('sync'))
  harness.job('job-3')
  release()
  await until(() => harness.calls.some((call) => call.startsWith('finished')))
  const calls = withoutScheduling(harness.calls)
  assert.ok(calls.indexOf('end') < calls.indexOf('resume'), calls.join(' '))
  assert.deepEqual(calls.slice(calls.indexOf('resume')), ['resume', 'ready', 'sync', 'stop', 'finished job-3 true'])
})

it('answers the job without syncing when the setting is off or the vault is not linked', async () => {
  store.clear()
  store.set('zn:background-sync', 'off')
  const off = await load()
  await installed(off)
  off.job('job-4')
  await until(() => off.calls.some((call) => call.startsWith('finished')))
  assert.deepEqual(off.calls, ['finished job-4 true'])

  store.clear()
  const unlinked = await load()
  await installed(unlinked, { ready: false })
  unlinked.job('job-5')
  await until(() => unlinked.calls.some((call) => call.startsWith('finished')))
  assert.deepEqual(unlinked.calls, ['resume', 'ready', 'finished job-5 true'])
})

it('answers a failed job run so the job ends', async () => {
  store.clear()
  const harness = await load()
  await installed(harness, {
    sync: async () => {
      throw new Error('offline')
    }
  })
  harness.api.leaveApp()
  await harness.api.backgroundSyncSettled()
  harness.calls.length = 0
  harness.job('job-6')
  await until(() => harness.calls.some((call) => call.startsWith('finished')))
  assert.deepEqual(harness.calls, ['resume', 'ready', 'stop', 'finished job-6 false'])
})
