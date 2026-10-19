import assert from 'node:assert/strict'
import { test } from 'node:test'
import { loadMobileModule } from '../../tooling/load-mobile-module.ts'

// Core 2.68.0 renamed the demo tour's notes: "00 Start Here.md" used to carry
// a long dash between number and title. Desktop's generateDemoTour and
// removeDemoTour (apps/desktop/src/main/vault.ts) take the notes still under
// the older names with them; this shell writes and removes the tour itself,
// so it has to as well. Both delete whatever the older-name match takes, so
// it must take nothing of the user's.

// Built from its code point so this file never contains the character itself.
const LONG_DASH = String.fromCharCode(0x2014)

Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => null } })
const { MobileVault, DEMO_TOUR_NOTES, DEMO_TOUR_ASSETS, DEMO_TOUR_START_PATH } = await loadMobileModule([
  './src/bridge/vault-fs', '@zennotes/shared-domain/demo-tour', '@zennotes/shared-domain/demo-tour-data'
])

const olderStart = `inbox/demo/00 ${LONG_DASH} Start Here.md`
const olderMath = `inbox/demo/05b ${LONG_DASH} Math Diagrams.md`
/** The user's notes: in the tour's folder or named like the tour, and not
 *  the tour. Another separator, a long-dash name the tour never shipped, or
 *  the old name outside the tour's folder all stay. */
const USER_NOTES = [
  'inbox/demo/My own note.md',
  'inbox/demo/00 · Start Here.md',
  'inbox/demo/01 - Markdown Basics.md',
  `inbox/demo/42 ${LONG_DASH} My Plans.md`,
  `inbox/00 ${LONG_DASH} Start Here.md`
]
const body = (path: string): string => `kept: ${path}\n`

/** The NativeFs surface the tour uses. Like NativeFs, a listing of a missing
 *  folder is empty and a delete of a missing file fails. */
class MemoryFs {
  files = new Map<string, string>()
  directories = new Set<string>()
  async mkdir(path: string): Promise<void> {
    const parts = path.split('/')
    for (let i = 1; i <= parts.length; i++) this.directories.add(parts.slice(0, i).join('/'))
  }
  async writeText(path: string, text: string): Promise<void> { this.files.set(path, text) }
  async deleteFile(path: string): Promise<void> {
    if (!this.files.delete(path)) throw new Error(`File does not exist: ${path}`)
  }
  async readdir(path: string): Promise<{ name: string; type: 'file' | 'directory' }[]> {
    const prefix = path ? `${path}/` : ''
    const entries = new Map<string, 'file' | 'directory'>()
    for (const key of this.files.keys()) {
      if (!key.startsWith(prefix)) continue
      const [name, ...nested] = key.slice(prefix.length).split('/')
      entries.set(name!, nested.length ? 'directory' : 'file')
    }
    for (const key of this.directories) {
      if (!key.startsWith(prefix)) continue
      const [name] = key.slice(prefix.length).split('/')
      if (name && !entries.has(name)) entries.set(name, 'directory')
    }
    return [...entries].map(([name, type]) => ({ name, type }))
  }
  async rmdir(path: string): Promise<void> {
    for (const key of [...this.files.keys()]) if (key.startsWith(`${path}/`)) this.files.delete(key)
    for (const key of [...this.directories]) if (key === path || key.startsWith(`${path}/`)) this.directories.delete(key)
  }
  namesIn(path: string): string[] {
    return [...this.files.keys()]
      .filter((key) => key.startsWith(`${path}/`) && !key.slice(path.length + 1).includes('/'))
      .map((key) => key.slice(path.length + 1))
      .sort()
  }
}

function vaultWith(paths: string[]) {
  const fs = new MemoryFs()
  for (const path of paths) {
    void fs.mkdir(path.slice(0, path.lastIndexOf('/')))
    fs.files.set(path, body(path))
  }
  const vault = new MobileVault('Fixture')
  Object.assign(vault, { fs, ensureVaultLayout: async () => {}, invalidateMeta: () => {} })
  return { fs, vault }
}

const tourPaths = (): string[] => DEMO_TOUR_NOTES.map((note: { path: string }) => note.path)
const userNamesInDemo = USER_NOTES.filter((path) => path.startsWith('inbox/demo/'))
  .map((path) => path.slice('inbox/demo/'.length))

test('the older names in these fixtures belong to notes the tour ships today', () => {
  assert.ok(tourPaths().includes(DEMO_TOUR_START_PATH))
  assert.ok(tourPaths().includes('inbox/demo/05b Math Diagrams.md'))
  assert.ok(!tourPaths().includes('inbox/demo/42 My Plans.md'))
})

test('regenerating replaces a tour under its older names instead of writing a second one', async () => {
  const { fs, vault } = vaultWith([olderStart, olderMath, ...USER_NOTES])
  const result = await vault.generateDemoTour()

  assert.equal(fs.files.has(olderStart), false)
  assert.equal(fs.files.has(olderMath), false)
  for (const note of DEMO_TOUR_NOTES) assert.equal(fs.files.get(note.path), note.body)
  for (const asset of DEMO_TOUR_ASSETS) assert.equal(fs.files.get(asset.path), asset.body)
  for (const path of USER_NOTES) assert.equal(fs.files.get(path), body(path), `${path} is the user's`)
  // One tour: the folder holds the tour's current notes and the user's, nothing else.
  // The tour's notes, and since core 2.71.0 its sample drawing beside them.
  const tourNames = [...tourPaths(), ...DEMO_TOUR_ASSETS.map((asset: { path: string }) => asset.path)]
    .filter((path) => path.startsWith('inbox/demo/') && !path.slice('inbox/demo/'.length).includes('/'))
    .map((path) => path.slice('inbox/demo/'.length))
  assert.deepEqual(fs.namesIn('inbox/demo'), [...tourNames, ...userNamesInDemo].sort())
  assert.deepEqual(result.notePaths, tourPaths())
})

test('removing takes the older names too, reports them, and keeps the user notes', async () => {
  const { fs, vault } = vaultWith([olderStart, olderMath, ...tourPaths(), ...USER_NOTES])
  const result = await vault.removeDemoTour()

  assert.deepEqual(fs.namesIn('inbox/demo'), [...userNamesInDemo].sort())
  for (const path of USER_NOTES) {
    assert.equal(fs.files.get(path), body(path), `${path} is the user's`)
    assert.ok(!result.notePaths.includes(path), `${path} is not reported as removed`)
  }
  assert.ok(result.notePaths.includes(olderStart))
  assert.ok(result.notePaths.includes(olderMath))
  assert.ok(result.notePaths.includes(DEMO_TOUR_START_PATH))
  assert.ok(fs.directories.has('inbox/demo'), 'the folder stays while the user has notes in it')
})

test('removing a tour that only exists under its older names leaves no folder behind', async () => {
  const { fs, vault } = vaultWith([olderStart, olderMath])
  await vault.removeDemoTour()

  assert.deepEqual(fs.namesIn('inbox/demo'), [])
  assert.equal(fs.directories.has('inbox/demo'), false)
})

test('removing the tour takes the sample database folder with it', async () => {
  // Core 2.71.0 ships a database, which is a folder of its own; the tour
  // folder could not go while that folder stayed behind.
  const { fs, vault } = vaultWith([])

  await vault.generateDemoTour()
  assert.ok(fs.directories.has('inbox/demo/Reading List.base'))
  await vault.removeDemoTour()

  assert.equal(fs.directories.has('inbox/demo/Reading List.base'), false)
  assert.equal(fs.directories.has('inbox/demo'), false)
})
