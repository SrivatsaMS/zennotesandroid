import assert from 'node:assert/strict'
import { afterEach, beforeEach, describe, it } from 'node:test'
import { loadMobileModule } from '../../tooling/load-mobile-module.ts'

const STORAGE: Record<string, string> = {
  EXTERNAL: '/storage/emulated/0/Android/data/md.zennotes/files',
  DATA: '/data/user/0/md.zennotes/files'
}

/** What getUri answers on Android (seen on an API 37 emulator): letters,
 *  digits, _-!.~'()* and the slashes stay, the rest is escaped as UTF-8, and
 *  an existing folder's URI ends in a slash. */
function androidFileUri(path: string, folder = false): string {
  return 'file://' + [...new TextEncoder().encode(path)].map((byte) => {
    const character = String.fromCharCode(byte)
    return /[A-Za-z0-9_\-!.~'()*/]/.test(character) ? character : '%' + byte.toString(16).toUpperCase().padStart(2, '0')
  }).join('') + (folder ? '/' : '')
}

describe('native file URIs for Cloud sync reads', () => {
  let stored: string | null
  let storage: PropertyDescriptor | undefined
  beforeEach(() => {
    stored = null
    storage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true, value: { getItem: () => stored, setItem: (_key: string, value: string) => { stored = value } }
    })
  })
  afterEach(() => {
    if (storage) Object.defineProperty(globalThis, 'localStorage', storage)
    else Reflect.deleteProperty(globalThis, 'localStorage')
  })

  async function nativeFs(options: { externalUsable?: boolean; folderSlash?: boolean } = {}) {
    const folders = new Set<string>()
    const getUris: string[] = []
    const safStats: string[] = []
    const inspected: string[] = []
    const { NativeFs, initVaultsRoot } = await loadMobileModule('./src/bridge/native-fs', {
      './icloud': { ensureDownloaded: async () => {} },
      '@capacitor/core': {
        Capacitor: {},
        registerPlugin: (name: string) => name === 'SafFs' ? {
          stat: async ({ path }: { path: string }) => {
            safStats.push(path)
            return { type: 'file', size: 1, mtime: 1, uri: `content://tree/document/${encodeURIComponent(path)}` }
          }
        } : {
          inspect: async ({ uri }: { uri: string }) => {
            inspected.push(uri)
            return { uri, sha256: '0'.repeat(64), byteLength: 1, utf8: true, inlineBase64: 'QQ==' }
          }
        }
      },
      '@capacitor/filesystem': {
        Directory: { Data: 'DATA', External: 'EXTERNAL' }, Encoding: { UTF8: 'utf8' },
        Filesystem: {
          mkdir: async ({ path, directory }: { path: string; directory: string }) => { folders.add(`${directory}:${path}`) },
          stat: async ({ directory }: { directory: string }) => {
            if (directory === 'EXTERNAL' && options.externalUsable === false) throw new Error('Missing parent directory')
            return { type: 'directory', size: 0, mtime: 1, uri: '' }
          },
          getUri: async ({ path, directory }: { path: string; directory: string }) => {
            getUris.push(path)
            const folder = options.folderSlash !== false && folders.has(`${directory}:${path}`)
            return { uri: androidFileUri(`${STORAGE[directory]}/${path}`, folder) }
          }
        }
      }
    })
    return { NativeFs, initVaultsRoot, getUris, safStats, inspected }
  }

  const names = [
    'note.md', 'Folder/Sub folder/Note 1.md', 'Été à Paris/café #2.md', 'what? 100% & more.md',
    "it's (done)!*~.md", 'a+b=c;d,e:f@g$.md', 'emoji 📝 [draft] {x}.md', '.zennotes/sync/write-1.md'
  ]

  for (const folderSlash of [true, false]) {
    it(`builds each local file URI from the root resolved at open, exactly as getUri names it${folderSlash ? '' : ' (root without a slash)'}`, async () => {
      const h = await nativeFs({ folderSlash })
      const fs = new h.NativeFs('My Vault #1')
      await fs.init()
      assert.equal(fs.rootUri.endsWith('/'), folderSlash)
      assert.deepEqual(h.getUris, ['ZenNotes/My Vault #1'])
      for (const name of names) await fs.readForSync(name, true)
      assert.deepEqual(h.getUris, ['ZenNotes/My Vault #1'])
      assert.deepEqual(h.inspected, names.map((name) => androidFileUri(`${STORAGE.EXTERNAL}/ZenNotes/My Vault #1/${name}`)))
    })
  }

  it('asks getUri before the vault is open, and when the root moved after it opened', async () => {
    const h = await nativeFs({ externalUsable: false })
    const unopened = new h.NativeFs('Notes')
    await unopened.readForSync('note.md', true)
    assert.deepEqual(h.getUris, ['ZenNotes/Notes/note.md'])

    const opened = new h.NativeFs('Notes')
    await opened.init()
    await h.initVaultsRoot()
    h.getUris.length = 0
    h.inspected.length = 0
    await opened.readForSync('note.md', true)
    assert.deepEqual(h.getUris, ['ZenNotes/Notes/note.md'])
    assert.deepEqual(h.inspected, [androidFileUri(`${STORAGE.DATA}/ZenNotes/Notes/note.md`)])
  })

  it('keeps a scanned URI and the external folder tier\'s document lookup', async () => {
    const h = await nativeFs()
    const local = new h.NativeFs('Notes')
    await local.init()
    await local.readForSync('note.md', true, 'file:///from/the/listing.md')
    const external = new h.NativeFs('Shared', 'content://tree/root')
    await external.init()
    await external.readForSync('Folder/note.md', true)
    assert.deepEqual(h.inspected, ['file:///from/the/listing.md', 'content://tree/document/Folder%2Fnote.md'])
    assert.deepEqual(h.safStats, ['Folder/note.md'])
    assert.deepEqual(h.getUris, ['ZenNotes/Notes'])
  })
})
