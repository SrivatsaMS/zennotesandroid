/**
 * Background sync: Settings → Cloud → Sync in the background, on by default.
 *
 * The sync engine runs in this WebView, which exists only while MainActivity
 * does, so Android cannot run it from a cold start. What this does:
 *
 * - When you leave ZenNotes, it finishes a sync before stopping, so an edit
 *   made just before leaving reaches your other devices without opening the
 *   app again. Android may freeze a cached app within seconds, so leaving
 *   also queues a catch-up run (`begin`), dropped once the sync ends (`end`).
 * - While the vault is linked, a WorkManager job runs about every 15 minutes
 *   and, if Android still has ZenNotes in memory, asks this module for a
 *   sync (the `sync` event); otherwise it does nothing and the next open
 *   syncs.
 *
 * Leaving used to stop every Cloud request at once (cloud-sync-client.ts
 * binds them to one lifetime), which abandoned a sync in progress. With the
 * setting on, requests stay open while background work runs and stop when
 * the last run ends; with it off, or before this module is installed,
 * leaving stops them at once, as before. Signing out stops them at once
 * either way. Kept close to the iPhone app's copy, which has the leaving
 * half only.
 */
import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'
import type { VaultChangeEvent } from '@zennotes/bridge-contract/ipc'
import { BACKGROUND_SYNC_KEY } from '../viewport'
import { resumeMobileCloudRequests, stopMobileCloudRequests } from './cloud-sync-client'

export { BACKGROUND_SYNC_KEY }

/** The editor writes a note 350 ms after the last keystroke (app-core's
 *  PATH_SAVE_DEBOUNCE_MS). Waiting a little longer lets an edit made just
 *  before leaving reach the disk before the sync reads the vault. */
const SAVE_SETTLE_MS = 500

/** A second pass runs only when the vault changed during the first. Never
 *  more: the sync's own rescan reports changes too. */
const MAX_PASSES = 2

/** WorkManager's shortest repeat interval. */
export const SYNC_EVERY_MINUTES = 15

export interface BackgroundSyncPlugin {
  /** Queues the catch-up run for a final sync Android freezes midway. */
  begin(): Promise<void>
  /** The final sync ended: drops the catch-up run. */
  end(): Promise<void>
  /** Runs the periodic job every `minutes` while the network is up. */
  schedule(options: { minutes: number }): Promise<void>
  cancel(): Promise<void>
  /** Answers a `sync` event, ending the job that sent it. */
  finished(options: { id: string; ok: boolean }): Promise<void>
  addListener(event: 'sync', listener: (event: { id: string }) => void): Promise<PluginListenerHandle>
}

/** What this module needs from the open vault (mobile-bridge.ts). */
export interface BackgroundSyncHost {
  /** Signed in, and the open vault is linked to this account's Cloud. */
  ready(): Promise<boolean>
  /** One sync pass of the open vault; joins a pass that is already running. */
  sync(): Promise<void>
  onVaultChange(listener: (event: VaultChangeEvent) => void): () => void
}

let host: BackgroundSyncHost | null = null
let plugin: BackgroundSyncPlugin | null = null
let active = true
let leaving: Promise<void> | null = null
let runs = 0

/** Registered on first use, so loading this module never touches native code. */
function native(): BackgroundSyncPlugin {
  plugin ??= registerPlugin<BackgroundSyncPlugin>('ZenBackgroundSync')
  return plugin
}

export function backgroundSyncEnabled(): boolean {
  try {
    return localStorage.getItem(BACKGROUND_SYNC_KEY) !== 'off'
  } catch {
    return true
  }
}

export function setBackgroundSyncEnabled(enabled: boolean): void {
  try {
    // On is the default, so it removes the key: a fresh install and a reset
    // look the same.
    if (enabled) localStorage.removeItem(BACKGROUND_SYNC_KEY)
    else localStorage.setItem(BACKGROUND_SYNC_KEY, 'off')
  } catch {
    // Storage unavailable: the choice applies to this session only.
  }
  void refreshSchedule()
}

export function installBackgroundSync(next: BackgroundSyncHost): void {
  if (host) return
  host = next
  void native()
    .addListener('sync', ({ id }) => void syncForJob(id))
    .catch(() => {})
  void refreshSchedule()
}

/** The app left the screen (Capacitor's appStateChange, isActive false). */
export function leaveApp(): void {
  active = false
  const current = host
  if (!current || !backgroundSyncEnabled()) {
    stopMobileCloudRequests()
    return
  }
  // Linking, unlinking and signing in or out all happen on screen, so
  // leaving is when the job's schedule catches up with them.
  void refreshSchedule()
  leaving ??= holdRequests(() => finishSync(current)).finally(() => {
    leaving = null
  })
}

/** The app is back on screen. */
export function returnToApp(): void {
  active = true
  resumeMobileCloudRequests()
}

/** Settles once the final sync started by leaving has ended (tests). */
export function backgroundSyncSettled(): Promise<void> {
  return leaving ?? Promise.resolve()
}

/** Background work keeps Cloud requests open; the last run to end stops them
 *  if the app is still away. */
async function holdRequests(work: () => Promise<void>): Promise<void> {
  runs += 1
  try {
    await work()
  } finally {
    runs -= 1
    if (runs === 0 && !active) stopMobileCloudRequests()
  }
}

async function finishSync(current: BackgroundSyncHost): Promise<void> {
  let began = false
  try {
    if (!(await current.ready())) return
    await native().begin()
    began = true
    await delay(SAVE_SETTLE_MS)
    await syncPasses(current)
  } catch (error) {
    // The next foreground sync picks it up; nothing here can be shown.
    console.warn('Background sync did not finish', error)
  } finally {
    if (began) await native().end().catch(() => {})
  }
}

/** A run of the periodic (or catch-up) job. Always answers, so the job ends. */
async function syncForJob(id: string): Promise<void> {
  let ok = false
  try {
    // A final sync from leaving goes first; this run then finds less to do.
    await leaving
    const current = host
    if (current && backgroundSyncEnabled()) {
      await holdRequests(async () => {
        resumeMobileCloudRequests()
        if (await current.ready()) await syncPasses(current)
      })
    }
    ok = true
  } catch (error) {
    console.warn('Background sync did not finish', error)
  } finally {
    await native().finished({ id, ok }).catch(() => {})
  }
}

async function syncPasses(current: BackgroundSyncHost): Promise<void> {
  let changed = false
  const stopWatching = current.onVaultChange(() => {
    changed = true
  })
  try {
    for (let pass = 0; pass < MAX_PASSES; pass += 1) {
      changed = false
      await current.sync()
      if (!changed) break
    }
  } finally {
    stopWatching()
  }
}

/** The job runs only while the setting is on and the vault is linked, so a
 *  phone without Cloud is never woken for it. */
async function refreshSchedule(): Promise<void> {
  const current = host
  if (!current) return
  try {
    if (backgroundSyncEnabled() && (await current.ready())) {
      await native().schedule({ minutes: SYNC_EVERY_MINUTES })
    } else {
      await native().cancel()
    }
  } catch (error) {
    console.warn('Background sync schedule was not updated', error)
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
