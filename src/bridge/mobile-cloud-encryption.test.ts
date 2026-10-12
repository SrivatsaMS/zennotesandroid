import assert from 'node:assert/strict'
import { it } from 'node:test'
import type { CloudEncryptionKeyset, CloudSyncVault } from '@zennotes/bridge-contract/cloud-sync'
import { generateCloudE2eeKey } from '@zennotes/shared-domain/cloud-e2ee'
import { CloudE2eeKeyring, type StoredCloudAccountKey } from '@zennotes/shared-domain/cloud-e2ee-account'
import { CloudVaultCipher } from '@zennotes/shared-domain/cloud-e2ee-vault'
import { loadMobileModule } from '../../tooling/load-mobile-module.ts'

const { createMobileCloudEncryption } = await loadMobileModule('./src/bridge/mobile-cloud-encryption.ts', {
  './mobile-cloud-auth': { MOBILE_CLOUD_END_TO_END_ENCRYPTION: false }
})

const PASSPHRASE = 'correct horse battery staple'
const NEXT_PASSPHRASE = 'a brand new passphrase here'

/** An account on a service that stores the keyset, and this device's key store and keyring. */
function account() {
  let keyset: CloudEncryptionKeyset | null = null
  let stored: StoredCloudAccountKey | null = null
  const vaults: CloudSyncVault[] = []
  const store = {
    load: async () => stored,
    save: async (value: StoredCloudAccountKey) => { stored = value },
    clear: async () => { stored = null }
  }
  const client = {
    account: async () => ({ data: { capabilities: { end_to_end_encryption: true }, encryption: { keyset: keyset !== null } } }),
    keysetApi: () => ({
      getKeyset: async () => keyset,
      createKeyset: async (body: Omit<CloudEncryptionKeyset, 'revision' | 'updated_at'>) => {
        assert.equal(keyset, null)
        keyset = { ...body, revision: 1, updated_at: '2026-10-12T00:00:00Z' }
        return keyset
      },
      updateKeyset: async ({ expected_revision: revision, ...changes }: { expected_revision: number }) => {
        assert.equal(revision, keyset?.revision)
        keyset = { ...keyset!, ...changes, revision: revision + 1 }
        return keyset
      }
    }),
    createEncryptedVault: async (name: string, accountKey: Uint8Array, version: number) => {
      const vault = {
        id: `vault-${vaults.length + 1}`, name: 'Encrypted vault', cursor: 0, created_at: '', updated_at: '',
        encryption: await CloudVaultCipher.prepareVault(name, accountKey, version)
      }
      vaults.push(vault)
      return { data: vault }
    },
    listVaults: async () => ({ data: vaults })
  }
  const keyring = new CloudE2eeKeyring({ listVaults: async () => vaults, store })
  return { client, store, keyring, vaults, get keyset() { return keyset } }
}

it('stays out of Settings and refuses encrypted vaults until this build opts in', async () => {
  const h = account()
  const elsewhere = generateCloudE2eeKey()
  h.vaults.push(
    { id: 'readable', name: 'Notes', cursor: 0, created_at: '', updated_at: '', encryption: null },
    { id: 'sealed', name: 'Encrypted vault', cursor: 0, created_at: '', updated_at: '',
      encryption: await CloudVaultCipher.prepareVault('Journal', elsewhere, 1) }
  )
  let accountRequests = 0
  const encryption = createMobileCloudEncryption({
    enabled: false,
    client: async () => ({ ...h.client, account: async () => { accountRequests++; return h.client.account() } }),
    keys: async () => assert.fail('a build without encryption never reads keys')
  })
  assert.deepEqual(await encryption.status(), { available: false, keyset: false, unlocked: false })
  assert.equal(accountRequests, 0)
  await assert.rejects(encryption.prepareSetup(PASSPHRASE), /not available in this version/)
  await assert.rejects(encryption.createEncryptedVault('Journal'), /not available in this version/)
  assert.equal(await encryption.isEncrypted('sealed'), false)
  await encryption.assertCanLink('readable')
  await assert.rejects(encryption.assertCanLink('sealed'), /cannot open/)
})

it('reports nothing available while signed out', async () => {
  const encryption = createMobileCloudEncryption({
    enabled: true, client: async () => null, keys: async () => assert.fail('no keys while signed out')
  })
  assert.deepEqual(await encryption.status(), { available: false, keyset: false, unlocked: false })
})

it('sets up, locks, unlocks, changes the passphrase and replaces the recovery key with the core\'s key work', async () => {
  const h = account()
  const encryption = createMobileCloudEncryption({
    enabled: true,
    client: async () => h.client,
    keys: async () => ({ store: h.store, keyring: h.keyring, scope: 'account-a' })
  })
  assert.deepEqual(await encryption.status(), { available: true, keyset: false, unlocked: false })

  const setup = await encryption.prepareSetup(PASSPHRASE)
  assert.equal(setup.recoveryWords.length, 24)
  assert.equal(h.keyset, null, 'nothing reaches the service before the words are confirmed')
  await encryption.finishSetup(setup.setupId)
  assert.deepEqual(await encryption.status(), { available: true, keyset: true, unlocked: true })
  await assert.rejects(encryption.finishSetup(setup.setupId), /no longer waiting/)

  const vaultId = await encryption.createEncryptedVault('  Journal  ')
  assert.equal(await h.keyring.vaultName(h.vaults[0]!), 'Journal')
  assert.equal(await encryption.isEncrypted(vaultId), true)
  await encryption.assertCanLink(vaultId)

  await encryption.lock()
  assert.equal((await encryption.status()).unlocked, false)
  await assert.rejects(encryption.assertCanLink(vaultId), { code: 'E2EE_LOCKED' })
  await assert.rejects(encryption.createEncryptedVault('Another'), /Unlock end-to-end encryption/)
  await encryption.unlock({ recoveryWords: setup.recoveryWords.join(' ') })
  await encryption.assertCanLink(vaultId)

  await encryption.changePassphrase({ passphrase: PASSPHRASE }, NEXT_PASSPHRASE)
  await encryption.lock()
  await assert.rejects(encryption.unlock({ passphrase: PASSPHRASE }), /does not unlock/)
  await encryption.unlock({ passphrase: NEXT_PASSPHRASE })

  const replaced = await encryption.prepareNewRecoveryKey({ passphrase: NEXT_PASSPHRASE })
  await encryption.finishNewRecoveryKey(replaced.setupId)
  await encryption.lock()
  await assert.rejects(encryption.unlock({ recoveryWords: setup.recoveryWords.join(' ') }), /does not unlock/)
  await encryption.unlock({ recoveryWords: replaced.recoveryWords.join(' ') })
  assert.equal((await encryption.status()).unlocked, true)
})

it('finishes a pending setup only under the credential that began it', async () => {
  const h = account()
  let scope = 'account-a'
  const encryption = createMobileCloudEncryption({
    enabled: true,
    client: async () => h.client,
    keys: async () => ({ store: h.store, keyring: h.keyring, scope })
  })
  const setup = await encryption.prepareSetup(PASSPHRASE)
  scope = 'account-b'
  await assert.rejects(encryption.finishSetup(setup.setupId), /no longer waiting/)
  assert.equal(h.keyset, null)
  assert.equal(await h.store.load(), null)
})
