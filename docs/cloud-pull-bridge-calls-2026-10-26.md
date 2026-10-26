# Cloud pull: fewer bridge calls per file, 26 October 2026

Branch `perf/cloud-pull-bridge-calls`, cut from `origin/main` at 3609c83
(1.1.46). Changes: `src/bridge/cloud-sync-repository.ts`,
`src/bridge/native-fs.ts`, `src/bridge/mobile-cloud-sync.ts`, and tests. No
native code, no Capacitor config and no app-core change.

## Why

A Discord report (yeminn) had phones sitting in "Syncing..." through a long
first Cloud sync. Measured on an API 37 emulator, a pull costs the same per
file at 600, 2,500 and 5,000 notes, and that cost is almost all Capacitor
bridge round trips: about 16 per pulled file at about 1 ms each, 10.3 s of a
10.7 s 600-note sync. So the lever for big first syncs is calls per file.

## What changed

Five calls per pulled file were redundant. Each is gone only where it could
not have guarded a file.

| Call | Where | Why it cannot lose a file |
|---|---|---|
| `deleteFile(backup)` on every write | `NativeCloudSyncContent.write` | A backup exists only when an original was moved aside (`before`). A new file never makes one, so the delete always failed into its `.catch`. Replacing a file still deletes its backup. |
| `stat(temporary)` after publishing | same | A rename that resolved moved the temporary onto the path. Capacitor's local rename either `renameTo`s or copies and then deletes the source, rejecting if that delete fails; SAF moves the document. Every path that did not publish still checks and removes the temporary. |
| `readdir(.zennotes/sync)` on every write | `assertReady` | Recovery files are now relisted only when one may have appeared: after a write that failed, after a backup that would not delete, and after any staged publish (which keeps its own backups). The first write, every scan and every staged download still list first. A completed write that removed its backup leaves nothing to find. |
| `Filesystem.getUri` before every `inspect` (local tier) | `NativeFs.readForSync` | `getUri` names a file by the root's URI plus the path with each segment escaped exactly as `encodeURIComponent` does. The root is resolved once at open, and the shortcut is used only for the same `Directory` it was resolved in; otherwise, and before open, it asks `getUri` as before. Checked on the emulator for 13 awkward names: identical strings and identical bytes. SAF vaults still stat for their document URI. |
| `mkdir(parent)` before every rename | `hostVault` in `mobile-cloud-sync.ts` | Each sync operation remembers the folders it has made. A rename into a folder that went missing fails before it changes anything (Capacitor checks the parent first; SAF makes it), and the cache forgets that folder so the next rename makes it again. The next sync starts with an empty cache. |

Kept as they were: the pre-publish `stat(path)` (a new file must still be
absent), both verifies (the temporary before publishing, the path after),
the original's verify before it moves aside, and every rollback.

One stricter behavior: a staged publish (large or referenced files) whose
rollback copy would not delete now stops the next write in the same run
with "Cloud sync needs recovery". Before, the run went on and the next sync's
scan stopped at the same file, so the user lands in the same place one step
sooner, the same way an inline write's leftover backup already behaved.

## Results

API 37 emulator (AVD `yeminn-conflict`, `-gpu host -cores 2 -memory 2048`),
fake Cloud fixture on the Mac, small inline notes in four folders. Fresh app
each run (`pm clear`, onboard, sign in, link), bridge calls counted by
wrapping `Capacitor.nativePromise`. Before and after APKs were built from the
same worktree, differing only in the three source files, and run
alternately. An earlier after build, which joined the root and the path with
a doubled slash (same bytes, same call count), ran 600 notes in 6.84 s and is
left out of the averages.

| Run | Before | After | Change |
|---|---|---|---|
| First sync, 600 notes | 10.13 s, 59.3 notes/s (3 runs) | 6.65 s, 90.4 notes/s (2 runs) | 34% less time |
| Change feed, 1,000 notes added (2 runs each) | 16.44 s, 60.8 notes/s | 11.12 s, 90.0 notes/s | 32% less time |
| First sync, 2,500 notes | 40.70 s, 61.5 notes/s (2 runs) | 28.68 s, 87.2 notes/s (1 run) | 30% less time |

Bridge calls per pulled file, first sync of 600:

| Call | Before | After |
|---|---|---|
| `Filesystem.stat` | 5.02 | 4.02 |
| `ZenDirectUpload.inspect` | 3.00 | 3.00 |
| `Filesystem.getUri` | 2.00 | 0 |
| `Filesystem.readdir` | 1.05 | 0.05 |
| `Filesystem.readFile` | 1.01 | 1.01 |
| `Filesystem.writeFile` | 1.00 | 1.00 |
| `Filesystem.mkdir` | 1.00 | 0.01 |
| `Filesystem.rename` | 1.00 | 1.00 |
| `Filesystem.deleteFile` | 1.00 | 0 |
| **Total** | **16.11** | **10.12** |

The change feed went from 16.5 to 10.5 calls per file, and 2,500 notes from
16.03 to 10.03.

Same results: the vault's files hashed identically (SHA-256 of every file
outside `.zennotes`) between before and after for every pair: 602 files at
600, 1,602 after the change feed, 2,502 at 2,500. `.zennotes/sync` was empty
after every run. Resolving the first-sync conflict with Cloud's version (a
staged publish over an existing note) landed the PC's text and left no
rollback file.

## What is left per file

About 10 calls: `stat` 4 (core's existence check, the write's own, the
pre-publish check, the post-sync scan's stability check), `inspect` 3 (both
verifies, and the scan reading the new file), and one each of `writeFile`,
`rename` and `readFile` (the app's rescan after sync). The core's check and
the write's own `stat` look alike but sit on either side of the core
boundary; merging them is a core API change. Seeding the scan cache from the
write would need its own before and after `stat` to stay trustworthy, which
costs what it saves.

## Tests

`npm test`: 322 pass (312 before, 10 new). `npm run typecheck` and
`npm run boundaries:check` clean.

- `cloud-sync-repository.test.ts`, "bridge calls per pulled file": the exact
  call list for a new file (one recovery listing, no backup delete, no
  temporary check); a replaced original is still verified, moved aside and its
  rollback copy deleted; a rollback copy that will not delete stops the next
  write; a failed write lists recovery files again; a staged publish that kept
  its rollback copy stops the next write.
- `native-fs-sync-uri.test.ts`: local file URIs come from the root resolved at
  open and match Android's `getUri` encoding for awkward names, with or without
  the folder slash; before open, or after the root moved, it asks `getUri`;
  a known URI and the SAF document lookup are unchanged.
- `mobile-cloud-sync.integration.test.ts`: each folder is made once per sync,
  and again on the next sync.

Against the old code, the call-count tests fail (as they should) and the
safety tests pass.

## How to test locally

1. `npm test` and `npm run typecheck` in this checkout.
2. On a device or emulator with a debug build, open the WebView over CDP
   (`adb forward tcp:9333 localabstract:webview_devtools_remote_$(adb shell pidof md.zennotes)`)
   and count calls during a sync:

   ```js
   const cap = window.Capacitor, stats = {}
   cap.__orig ??= cap.nativePromise
   cap.nativePromise = function (plugin, method, options) {
     const s = (stats[`${plugin}.${method}`] ??= { n: 0 })
     s.n++
     return cap.__orig.call(this, plugin, method, options)
   }
   const summary = await window.zen.syncCloudVault()
   Object.entries(stats).map(([k, v]) => `${k} ${(v.n / summary.pulled).toFixed(2)}`)
   ```

   A sync that pulls new notes should show no `getUri` or `deleteFile` and
   about 10 calls per file in total.
3. Pull a note into a new folder and edit a pulled note on another device,
   then sync: both land, and `.zennotes/sync` holds nothing but an empty
   `downloads` folder afterwards.
