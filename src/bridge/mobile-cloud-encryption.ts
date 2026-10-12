/**
 * End-to-end encryption for the signed-in Cloud account, as the shared
 * Settings panel (app-core's CloudEncryptionPanel) asks for it: set up, unlock,
 * change the passphrase, replace the recovery key, lock, and create encrypted
 * vaults. The key work is the core's (cloud-e2ee-account); this module supplies
 * the account's keyset API, this device's key store and its keyring.
 */
import type {
  CloudEncryptionRecoveryPrompt,
  CloudEncryptionSecretInput,
  CloudEncryptionStatus,
  CloudSyncVault
} from '@zennotes/bridge-contract/cloud-sync'
import type { CloudSyncApiClient } from '@zennotes/shared-domain/cloud-sync-api'
import { fromBase64 } from '@zennotes/shared-domain/cloud-e2ee'
import {
  changeCloudEncryptionPassphrase,
  prepareCloudEncryptionSetup,
  prepareNewCloudRecoveryKey,
  unlockCloudEncryption,
  type CloudAccountKeyStore,
  type CloudE2eeKeyring,
  type PendingCloudEncryptionSetup
} from '@zennotes/shared-domain/cloud-e2ee-account'
import {
  MOBILE_CLOUD_END_TO_END_ENCRYPTION,
  authenticatedClient,
  authenticatedCredential,
  getMobileCloudAccountStatus,
  mobileCloudAccountKeyStore,
  mobileCloudCredentialScope,
  mobileCloudKeyring
} from './mobile-cloud-auth'
import { randomUUID } from './uuid'

export const ENCRYPTED_VAULT_BACKUP_DOWNLOAD_MESSAGE =
  'Download backups of encrypted vaults from the ZenNotes desktop app.'

export type MobileCloudEncryptionClient = Pick<
  CloudSyncApiClient,
  'account' | 'keysetApi' | 'createEncryptedVault' | 'listVaults'
>

/** This device's side of the signed-in account's encryption. */
export interface MobileCloudEncryptionKeys {
  store: CloudAccountKeyStore
  keyring: Pick<CloudE2eeKeyring, 'remember' | 'forVault' | 'forget'>
  /** Which signed-in credential this is; a pending setup finishes only under the one that began it. */
  scope: string
}

export interface MobileCloudEncryptionDependencies {
  /** The host opted in: every path an encrypted vault's bytes take is sealed and opened here. */
  enabled: boolean
  /** The signed-in account's client, or null while no account is signed in. */
  client(): Promise<MobileCloudEncryptionClient | null>
  keys(): Promise<MobileCloudEncryptionKeys>
}

const NOT_AVAILABLE: CloudEncryptionStatus = { available: false, keyset: false, unlocked: false }

export function createMobileCloudEncryption(deps: MobileCloudEncryptionDependencies) {
  /** Setups and recovery-key changes waiting for the person to confirm their words. */
  const pending = new Map<string, { scope: string; setup: PendingCloudEncryptionSetup }>()

  async function connection(): Promise<{ client: MobileCloudEncryptionClient } & MobileCloudEncryptionKeys> {
    if (!deps.enabled) throw new Error('End-to-end encryption is not available in this version of ZenNotes.')
    const client = await deps.client()
    if (!client) throw new Error('Connect ZenNotes Cloud before setting up encryption.')
    return { client, ...(await deps.keys()) }
  }

  function remember(scope: string, setup: PendingCloudEncryptionSetup): CloudEncryptionRecoveryPrompt {
    const setupId = randomUUID()
    pending.set(setupId, { scope, setup })
    return { setupId, recoveryWords: setup.recoveryWords }
  }

  async function finish(setupId: string): Promise<void> {
    const waiting = pending.get(setupId)
    const current = await connection()
    if (!waiting || waiting.scope !== current.scope) {
      pending.delete(setupId)
      throw new Error('That recovery key is no longer waiting to be confirmed. Start again.')
    }
    await waiting.setup.finish()
    pending.delete(setupId)
  }

  async function vault(vaultId: string): Promise<CloudSyncVault | null> {
    const client = await deps.client()
    if (!client) return null
    return (await client.listVaults()).data.find((candidate) => candidate.id === vaultId) ?? null
  }

  return {
    async status(): Promise<CloudEncryptionStatus> {
      if (!deps.enabled) return NOT_AVAILABLE
      const client = await deps.client()
      if (!client) return NOT_AVAILABLE
      const account = (await client.account()).data
      const { store } = await deps.keys()
      return {
        available: account.capabilities?.end_to_end_encryption === true,
        keyset: account.encryption?.keyset === true,
        unlocked: (await store.load()) !== null
      }
    },

    /** Derives the keys and returns the recovery words; nothing reaches ZenNotes Cloud until finishSetup. */
    async prepareSetup(passphrase: string): Promise<CloudEncryptionRecoveryPrompt> {
      const { client, store, scope } = await connection()
      return remember(scope, await prepareCloudEncryptionSetup(client.keysetApi(), store, passphrase))
    },

    finishSetup: finish,

    async unlock(secret: CloudEncryptionSecretInput): Promise<void> {
      const { client, store, keyring } = await connection()
      await unlockCloudEncryption(client.keysetApi(), store, secret)
      keyring.forget()
    },

    async changePassphrase(current: CloudEncryptionSecretInput, next: string): Promise<void> {
      const { client, store } = await connection()
      await changeCloudEncryptionPassphrase(client.keysetApi(), store, current, next)
    },

    async prepareNewRecoveryKey(current: CloudEncryptionSecretInput): Promise<CloudEncryptionRecoveryPrompt> {
      const { client, store, scope } = await connection()
      return remember(scope, await prepareNewCloudRecoveryKey(client.keysetApi(), store, current))
    },

    finishNewRecoveryKey: finish,

    /** Forgets this device's account key; encrypted vaults stop syncing until it unlocks again. */
    async lock(): Promise<void> {
      if (!(await deps.client())) return
      const { store, keyring } = await deps.keys()
      await store.clear()
      keyring.forget()
    },

    /** Creates an encrypted vault and returns its id; its real name is sealed. */
    async createEncryptedVault(name: string): Promise<string> {
      const normalized = name.trim()
      if (!normalized) throw new Error('Cloud vault name is required.')
      const { client, store, keyring } = await connection()
      const stored = await store.load()
      if (!stored) throw new Error('Unlock end-to-end encryption on this device first.')
      const created = (await client.createEncryptedVault(normalized, fromBase64(stored.key), stored.version)).data
      keyring.remember(created)
      return created.id
    },

    /**
     * Linking an encrypted vault needs its key, so a device that cannot open
     * it stops here instead of at the first sync.
     */
    async assertCanLink(vaultId: string): Promise<void> {
      const listed = await vault(vaultId)
      if (!listed?.encryption) return
      if (!deps.enabled) {
        throw new Error('This vault is end-to-end encrypted, which this version of ZenNotes cannot open.')
      }
      const { keyring } = await connection()
      keyring.remember(listed)
      await keyring.forVault(listed.id)
    },

    async isEncrypted(vaultId: string): Promise<boolean> {
      // A build that cannot open encrypted vaults refuses to link one.
      if (!deps.enabled) return false
      return Boolean((await vault(vaultId))?.encryption)
    }
  }
}

const encryption = createMobileCloudEncryption({
  enabled: MOBILE_CLOUD_END_TO_END_ENCRYPTION,
  async client() {
    if ((await getMobileCloudAccountStatus()).state !== 'connected') return null
    return authenticatedClient()
  },
  async keys() {
    const credential = await authenticatedCredential()
    return {
      store: mobileCloudAccountKeyStore(credential.base_url, credential.account.user.email),
      keyring: mobileCloudKeyring(credential),
      scope: mobileCloudCredentialScope(credential)
    }
  }
})

export const getMobileCloudEncryptionStatus = (): Promise<CloudEncryptionStatus> => encryption.status()
export const prepareMobileCloudEncryptionSetup = (passphrase: string): Promise<CloudEncryptionRecoveryPrompt> =>
  encryption.prepareSetup(passphrase)
export const finishMobileCloudEncryptionSetup = (setupId: string): Promise<void> => encryption.finishSetup(setupId)
export const unlockMobileCloudEncryption = (secret: CloudEncryptionSecretInput): Promise<void> => encryption.unlock(secret)
export const changeMobileCloudEncryptionPassphrase = (current: CloudEncryptionSecretInput, next: string): Promise<void> =>
  encryption.changePassphrase(current, next)
export const prepareNewMobileCloudRecoveryKey = (current: CloudEncryptionSecretInput): Promise<CloudEncryptionRecoveryPrompt> =>
  encryption.prepareNewRecoveryKey(current)
export const finishNewMobileCloudRecoveryKey = (setupId: string): Promise<void> => encryption.finishNewRecoveryKey(setupId)
export const lockMobileCloudEncryption = (): Promise<void> => encryption.lock()
export const createMobileEncryptedCloudVault = (name: string): Promise<string> => encryption.createEncryptedVault(name)
export const assertMobileCloudVaultCanLink = (vaultId: string): Promise<void> => encryption.assertCanLink(vaultId)
export const isMobileCloudVaultEncrypted = (vaultId: string): Promise<boolean> => encryption.isEncrypted(vaultId)
