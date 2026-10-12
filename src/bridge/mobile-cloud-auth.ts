import { App as CapApp } from '@capacitor/app'
import { Capacitor, CapacitorHttp } from '@capacitor/core'
import {
  KeychainAccess,
  SecureStorage
} from '@aparajita/capacitor-secure-storage'
import type {
  CloudAccountConnectResult,
  CloudAccountStatus,
  CloudPublishedNote,
  CloudPublishedNoteResult,
  CloudPublishNoteInput,
  CloudServiceAccount,
  CloudSyncVault
} from '@zennotes/bridge-contract/cloud-sync'
import {
  CloudAuthFlow,
  parseCloudAuthCallback,
  type CloudAuthCredential,
  type CloudAuthExchangeRequest,
  type CloudAuthPending,
  type CloudAuthStorage
} from '@zennotes/shared-domain/cloud-auth-flow'
import {
  CloudE2eeKeyring,
  type CloudAccountKeyStore,
  type StoredCloudAccountKey
} from '@zennotes/shared-domain/cloud-e2ee-account'
import { createCloudSyncClient, stopMobileCloudRequests, resumeMobileCloudRequests, mobileCloudRequestSignal } from './cloud-sync-client'
import { publishWithStagedUploads, type CloudPublishAssetPlatform } from '@zennotes/shared-domain/cloud-publish-uploads'

/**
 * Whether this build lets ZenNotes Cloud serve it end-to-end encrypted vaults
 * (docs/specs/cloud-e2ee.md in the desktop repo). It may be on only while every
 * path an encrypted vault's bytes can take is sealed and opened on this
 * device: the core seals inline content, mobile-direct-upload seals streamed
 * uploads natively, and the shared download staging opens references
 * natively. Off, the client never sends the encryption header, so the service
 * refuses encrypted vaults to this app, and Settings never offers encryption.
 */
export const MOBILE_CLOUD_END_TO_END_ENCRYPTION = true

const DEVELOPMENT_CLOUD_BASE_URL = import.meta.env.VITE_ZENNOTES_CLOUD_DEV_URL?.trim()
const PRODUCTION_CLOUD_BASE_URL = 'https://zennotes.org'
const LEGACY_CLOUD_BASE_URL = 'https://zennotes.laravel.cloud'
const DEFAULT_CLOUD_BASE_URL = DEVELOPMENT_CLOUD_BASE_URL || PRODUCTION_CLOUD_BASE_URL
const PENDING_AUTH_KEY = 'pending-auth'
const CREDENTIAL_KEY = 'credential'
/** One entry per account: a fingerprint of the base URL and the lowercased email follows. */
const ACCOUNT_KEY_PREFIX = 'e2ee-account-key.'
const accountListeners = new Set<(status: CloudAccountStatus) => void>()

let authFlow: CloudAuthFlow | null = null
let callbackQueue = Promise.resolve()
let appActive = true

// Lazy and retryable so a transient native storage failure does not poison
// every later account read for the session.
let secureStorageSetup: Promise<void> | null = null
function secureStorageReady(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return Promise.resolve()
  if (!secureStorageSetup) {
    secureStorageSetup = configureSecureStorage()
    secureStorageSetup.catch(() => {
      secureStorageSetup = null
    })
  }
  return secureStorageSetup
}

async function configureSecureStorage(): Promise<void> {
  // The Capacitor proxy loads its implementation lazily. Parallel first
  // calls can initialize separate instances and lose the configured prefix.
  await SecureStorage.setKeyPrefix('zennotes.cloud.')
  await SecureStorage.setSynchronize(false)
  await SecureStorage.setDefaultKeychainAccess(KeychainAccess.whenUnlockedThisDeviceOnly)
  await migrateAuthStoragePrefix()
}

async function migrateAuthStoragePrefix(): Promise<void> {
  const keys = [CREDENTIAL_KEY, PENDING_AUTH_KEY]
  const canonical = new Map<string, string | null>()
  for (const key of keys) canonical.set(key, await SecureStorage.getItem(key))

  // Affected builds could persist auth under the plugin's default prefix.
  // All storage callers await setup, so none can see this temporary prefix.
  // CloudAuthFlow still validates every recovered record before using it.
  try {
    await SecureStorage.setKeyPrefix('capacitor-storage_')
    const legacy = new Map<string, string>()
    for (const key of keys) {
      const value = await SecureStorage.getItem(key)
      if (value !== null) legacy.set(key, value)
    }
    await SecureStorage.setKeyPrefix('zennotes.cloud.')
    for (const [key, value] of legacy) {
      if (canonical.get(key) === null) await SecureStorage.setItem(key, value)
    }
    // Remove superseded records as well, so logout cannot resurrect an older
    // credential on the next launch. Copying must finish before removal.
    await SecureStorage.setKeyPrefix('capacitor-storage_')
    for (const key of legacy.keys()) await SecureStorage.removeItem(key)
  } finally {
    await SecureStorage.setKeyPrefix('zennotes.cloud.')
  }
}

const storage: CloudAuthStorage = {
  async loadPending(): Promise<unknown> {
    if (!Capacitor.isNativePlatform()) return null
    await secureStorageReady()
    return parseStoredJson(await SecureStorage.getItem(PENDING_AUTH_KEY))
  },
  async savePending(pending: CloudAuthPending): Promise<void> {
    assertNativeCloudAuth()
    await secureStorageReady()
    await SecureStorage.setItem(PENDING_AUTH_KEY, JSON.stringify(pending))
  },
  async deletePending(): Promise<void> {
    if (!Capacitor.isNativePlatform()) return
    await secureStorageReady()
    await SecureStorage.removeItem(PENDING_AUTH_KEY)
  },
  async loadCredential(): Promise<unknown> {
    if (!Capacitor.isNativePlatform()) return null
    await secureStorageReady()
    const credential = migrateLegacyCloudCredential(
      parseStoredJson(await SecureStorage.getItem(CREDENTIAL_KEY))
    )
    if (credential.migrated) {
      await SecureStorage.setItem(CREDENTIAL_KEY, JSON.stringify(credential.value))
    }
    return credential.value
  },
  async saveCredential(credential: CloudAuthCredential): Promise<void> {
    stopMobileCloudRequests()
    forgetKeyring()
    assertNativeCloudAuth()
    await secureStorageReady()
    const canonicalCredential = migrateLegacyCloudCredential(credential).value as CloudAuthCredential
    // Another account's encryption key does not outlive a switch to this one;
    // signing in again to the same account keeps this device unlocked. Each
    // entry belongs to one account and can never unlock another, so a failed
    // sweep must not block signing in; signing out sweeps again and must work.
    await clearAccountKeys(
      await accountKeyName(canonicalCredential.base_url, canonicalCredential.account.user.email)
    ).catch(() => {})
    await SecureStorage.setItem(CREDENTIAL_KEY, JSON.stringify(canonicalCredential))
    if (appActive) resumeMobileCloudRequests()
  },
  async deleteCredential(): Promise<void> {
    stopMobileCloudRequests()
    forgetKeyring()
    if (!Capacitor.isNativePlatform()) return
    await secureStorageReady()
    // Signing out also forgets this device's encryption keys; the passphrase
    // or the recovery key unlocks it again.
    await clearAccountKeys()
    await SecureStorage.removeItem(CREDENTIAL_KEY)
  }
}

async function clearAccountKeys(keep: string | null = null): Promise<void> {
  for (const key of await SecureStorage.keys()) {
    if (key.startsWith(ACCOUNT_KEY_PREFIX) && key !== keep) await SecureStorage.removeItem(key)
  }
}

async function accountKeyName(baseUrl: string, email: string): Promise<string> {
  const scope = `${baseUrl.trim().replace(/\/+$/, '')}\n${email.trim().toLowerCase()}`
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(scope))
  return ACCOUNT_KEY_PREFIX + [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

/**
 * This device's unlocked end-to-end encryption key for one account, kept in
 * the Keystore-backed storage that holds the credential. A missing or
 * unreadable entry reads as locked: with allowBackup, a restored install gets
 * the stored value back but not the Keystore key that decrypts it, and that
 * must ask for the passphrase again rather than fail on every read.
 */
export function mobileCloudAccountKeyStore(baseUrl: string, email: string): CloudAccountKeyStore {
  return {
    async load(): Promise<StoredCloudAccountKey | null> {
      if (!Capacitor.isNativePlatform()) return null
      try {
        await secureStorageReady()
        return parseStoredAccountKey(await SecureStorage.getItem(await accountKeyName(baseUrl, email)))
      } catch {
        return null
      }
    },
    async save(value: StoredCloudAccountKey): Promise<void> {
      assertNativeCloudAuth()
      await secureStorageReady()
      await SecureStorage.setItem(await accountKeyName(baseUrl, email), JSON.stringify(value))
    },
    async clear(): Promise<void> {
      if (!Capacitor.isNativePlatform()) return
      await secureStorageReady()
      await SecureStorage.removeItem(await accountKeyName(baseUrl, email))
    }
  }
}

function parseStoredAccountKey(raw: string | null): StoredCloudAccountKey | null {
  if (raw === null) return null
  try {
    const value = JSON.parse(raw) as Partial<StoredCloudAccountKey> | null
    // A 32-byte key is 44 base64 characters; anything else could never open a vault.
    return value?.format === 1 && typeof value.key === 'string' && /^[A-Za-z0-9+/]{43}=$/.test(value.key) &&
      typeof value.version === 'number' && Number.isInteger(value.version) && value.version >= 0
      ? { format: 1, key: value.key, version: value.version }
      : null
  } catch {
    return null
  }
}

let keyringCache: { scope: string; keyring: CloudE2eeKeyring } | null = null

/** Which signed-in credential this is: the account, and the token that signs in as it. */
export function mobileCloudCredentialScope(credential: CloudAuthCredential): string {
  return JSON.stringify([credential.base_url, credential.account.user.email.toLowerCase(), credential.token])
}

/**
 * One keyring per signed-in credential, so the "is this vault encrypted"
 * answers and opened ciphers last across calls but never across accounts.
 */
export function mobileCloudKeyring(credential: CloudAuthCredential): CloudE2eeKeyring {
  const scope = mobileCloudCredentialScope(credential)
  if (keyringCache?.scope === scope) return keyringCache.keyring
  forgetKeyring()
  const keyring = new CloudE2eeKeyring({
    listVaults: async () => (await createCloudSyncClient(credential.base_url, credential.token, {
      accountId: credential.account.user.email,
      signal: mobileCloudRequestSignal()
    }).listVaults()).data,
    store: mobileCloudAccountKeyStore(credential.base_url, credential.account.user.email)
  })
  keyringCache = { scope, keyring }
  return keyring
}

function forgetKeyring(): void {
  keyringCache?.keyring.forget()
  keyringCache = null
}

export async function configureMobileCloudAuth(appVersion: string): Promise<void> {
  if (authFlow) return

  authFlow = new CloudAuthFlow({
    platform: 'android',
    appVersion,
    deviceName: 'ZenNotes Android',
    storage,
    openExternal: openExternalBrowser,
    exchange: exchangeCloudAuthCode,
    revoke: revokeMobileCloudDevice,
    allowInsecureLoopback: import.meta.env.DEV,
    allowedInsecureOrigins: DEVELOPMENT_CLOUD_BASE_URL
      ? [DEVELOPMENT_CLOUD_BASE_URL]
      : undefined
  })

  await CapApp.addListener('appUrlOpen', ({ url }) => {
    if (isCloudAuthUrl(url)) scheduleAuthCallback(url)
  })
  await CapApp.addListener('appStateChange', ({ isActive }) => {
    appActive = isActive
    if (isActive) resumeMobileCloudRequests()
    else stopMobileCloudRequests()
  })
  const launch = await CapApp.getLaunchUrl()
  if (launch?.url && isCloudAuthUrl(launch.url)) scheduleAuthCallback(launch.url)
}

/** The scheme is shared with the widget links (ui-mobile/widget-links.ts);
 *  only `zennotes://auth…` is this module's to handle. */
function isCloudAuthUrl(rawUrl: string): boolean {
  try {
    const parsed = new URL(rawUrl.trim())
    return (
      parsed.protocol === 'zennotes:' &&
      (parsed.hostname || parsed.pathname.replace(/^\/+/, '')) === 'auth'
    )
  } catch {
    return false
  }
}

export async function getMobileCloudAccountStatus(): Promise<CloudAccountStatus> {
  return requireAuthFlow().status()
}

export async function connectMobileCloudAccount(
  baseUrl = DEFAULT_CLOUD_BASE_URL
): Promise<CloudAccountConnectResult> {
  assertNativeCloudAuth()
  const result = await requireAuthFlow().connect(baseUrl)
  await notifyAccountListeners()
  return result
}

export async function logoutMobileCloudAccount(): Promise<CloudAccountStatus> {
  stopMobileCloudRequests()
  const status = await requireAuthFlow().logout()
  notify(status)
  return status
}

export function onMobileCloudAccountChange(
  listener: (status: CloudAccountStatus) => void
): () => void {
  accountListeners.add(listener)
  return () => accountListeners.delete(listener)
}

export async function getMobileCloudServiceAccount(): Promise<CloudServiceAccount> {
  return (await authenticatedClient()).account().then((response) => response.data)
}

export async function listMobileCloudPublishedNotes(): Promise<CloudPublishedNote[]> {
  return (await authenticatedClient()).listPublishedNotes().then((response) => response.data)
}

/** Publish with the note's attachments staged straight to storage first
 *  (shared-domain's publishWithStagedUploads); a service without staged
 *  uploads gets the one-request publish. */
export async function publishMobileCloudNote<Handle>(
  input: CloudPublishNoteInput,
  assets: CloudPublishAssetPlatform<Handle>
): Promise<CloudPublishedNoteResult> {
  return await publishWithStagedUploads(await authenticatedClient(), input, assets)
}

export async function updateMobileCloudPublishedNote<Handle>(
  shareId: number,
  input: CloudPublishNoteInput,
  assets: CloudPublishAssetPlatform<Handle>
): Promise<CloudPublishedNoteResult> {
  return await publishWithStagedUploads(await authenticatedClient(), input, assets, { shareId })
}

export async function unpublishMobileCloudNote(shareId: number): Promise<void> {
  await (await authenticatedClient()).unpublishNote(shareId)
}

export async function listMobileCloudVaults(): Promise<CloudSyncVault[]> {
  return (await authenticatedClient()).listVaults().then((response) => response.data)
}

export async function authenticatedClient() {
  const signal = mobileCloudRequestSignal()
  const credential = await authenticatedCredential()
  if (signal.aborted) throw new DOMException('Cloud account changed while loading credentials.', 'AbortError')
  return createCloudSyncClient(credential.base_url, credential.token, {
    accountId: credential.account.user.email,
    signal,
    ...(MOBILE_CLOUD_END_TO_END_ENCRYPTION ? { keyring: mobileCloudKeyring(credential) } : {})
  })
}

export async function authenticatedCredential(): Promise<CloudAuthCredential> {
  const credential = await requireAuthFlow().credential()
  if (!credential) throw new Error('Connect ZenNotes Cloud before using sync.')
  return credential
}

function scheduleAuthCallback(rawUrl: string): void {
  const callback = parseCloudAuthCallback(rawUrl)
  if (!callback) return

  callbackQueue = callbackQueue
    .then(async () => {
      const status = await requireAuthFlow().complete(callback)
      notify(status)
    })
    .catch(() => undefined)
}

async function notifyAccountListeners(): Promise<void> {
  notify(await requireAuthFlow().status())
}

function notify(status: CloudAccountStatus): void {
  for (const listener of accountListeners) listener(status)
}

function requireAuthFlow(): CloudAuthFlow {
  if (!authFlow) throw new Error('ZenNotes Cloud is not ready yet.')
  return authFlow
}

function assertNativeCloudAuth(): void {
  if (!Capacitor.isNativePlatform()) {
    throw new Error('ZenNotes Cloud sign-in is only available in the native app.')
  }
}

async function openExternalBrowser(url: string): Promise<void> {
  window.open(url, '_blank', 'noopener,noreferrer')
}

/**
 * Ask the service to end this device's access while the token still exists
 * to ask with, as Connected apps on the website does. CloudAuthFlow signs out
 * whatever the answer, and gives up waiting after a few seconds.
 */
async function revokeMobileCloudDevice(baseUrl: string, token: string): Promise<void> {
  const response = await CapacitorHttp.request({
    method: 'DELETE',
    url: `${baseUrl}/api/v1/devices/current`,
    headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
    connectTimeout: 10_000,
    readTimeout: 10_000,
    disableRedirects: true
  })
  // 401 means the token is already gone, which is what was asked for.
  if ((response.status < 200 || response.status >= 300) && response.status !== 401) {
    throw new Error(`ZenNotes Cloud did not disconnect this device (${response.status}).`)
  }
}

async function exchangeCloudAuthCode(
  baseUrl: string,
  request: CloudAuthExchangeRequest
): Promise<unknown> {
  const response = await CapacitorHttp.post({
    url: `${baseUrl}/api/v1/app/exchange`,
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    data: request,
    connectTimeout: 30_000,
    readTimeout: 30_000
  })

  if (response.status < 200 || response.status >= 300) {
    const payload = isRecord(response.data) ? response.data : {}
    const error = isRecord(payload.error) ? payload.error : {}
    const message =
      typeof error.message === 'string'
        ? error.message
        : typeof payload.message === 'string'
          ? payload.message
          : 'ZenNotes Cloud rejected this sign-in request.'
    throw new Error(message)
  }

  return response.data
}

function parseStoredJson(value: string | null): unknown {
  if (value === null) return null
  try {
    return JSON.parse(value) as unknown
  } catch {
    return {}
  }
}

function migrateLegacyCloudCredential(value: unknown): { migrated: boolean; value: unknown } {
  if (!isRecord(value) || value.base_url !== LEGACY_CLOUD_BASE_URL || !isRecord(value.account)) {
    return { migrated: false, value }
  }

  return {
    migrated: true,
    value: {
      ...value,
      base_url: PRODUCTION_CLOUD_BASE_URL,
      account: {
        ...value.account,
        base_url: PRODUCTION_CLOUD_BASE_URL
      }
    }
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object'
}
