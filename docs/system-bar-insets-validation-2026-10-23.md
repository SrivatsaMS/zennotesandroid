# System bar insets: verification, 23 October 2026

Branch `fix/system-bar-insets`, cut from `origin/main` at 65fda06 (1.1.45).
Committed locally, not pushed. Changes: `src/ui-mobile/mobile.css` and two
new contract tests in `src/ui-mobile/mobile-layout.test.ts`. No native code,
no Capacitor config and no app-core change.

## Report

Dave (Discord, 2026-10-23, Android 1.1.45 from Play, 3-button navigation):
the navigation buttons make app buttons ("Go back" and others) hard to tap.
His screenshots of Cloud's **Review sync changes** sheet show its last line,
"Loading the versions kept for you…", drawn under back/home/recents.

## Cause

Capacitor 8's SystemBars plugin picks one of two inset models by WebView
version (`SystemBars.java`, `insetsHandling: css`, the default):

- **WebView < 140:** it pads the WebView natively, so the page sits between
  the bars and every `env(safe-area-inset-*)` reads 0.
- **WebView 140 and later** (with `viewport-fit=cover`, which `index.html`
  has): it drops the native padding and hands the insets to the page. The
  page now draws under the status bar and the navigation bar, and
  `env(safe-area-inset-*)` carries the real values.

The emulators used so far ship WebView 133, so the shell's CSS was only ever
seen in the first model. Phones that update WebView from Play are in the
second, where three things were wrong:

1. **Dialog sheets overflow into the bottom inset.** `mobile.css` turns core
   dialogs into bottom sheets capped at `86dvh`, with the bar's height as
   `padding-bottom` *inside* that cap (border-box). Core sizes some dialog
   bodies in `vh` for the desktop: Review sync changes has a `72vh`
   scroller under its header. Once the body reaches its cap, header + body +
   inset exceed `86dvh`, and since `overflow: hidden` clips at the padding
   edge, the body's bottom paints straight into the strip under the bar. On a
   360x800 phone the body ran 56 px past the sheet's content box: exactly
   Dave's screenshot, and the reason buttons that scroll through that strip
   ("Go back", "Keep this version") are hard to hit.
2. **Full-screen surfaces ignore the bottom inset.** The search/command
   palettes' results run to the screen bottom (the last rows can never
   scroll clear of the bar), and Settings' footer note sits under it.
3. **Nothing honors the side insets.** In landscape, 3-button navigation
   moves to the right edge (left at 270 degrees) and the camera cutout takes
   the other side. The app shell, the More/Create sheets, dialog sheets,
   palettes, Settings, side panels, the drawer and toasts all ran under them
   ("Finish later" sat under Back).

`env()` itself is reliable on WebView 145: measured top 51 / bottom 48 with
3-button navigation, bottom 24 with gestures, and bottom 0 while the
keyboard is up, matching Capacitor's injected `--safe-area-inset-*`.

## Fix (shell CSS only)

- Dialog sheets: block panels become a flex column, so a vh-capped scroller
  (the dialog body) shrinks to fit the cap and the header and footer stay in
  view; panels core already lays out as `flex` or `grid` are left alone.
  `overflow-y: auto` replaces the clip, so anything that still does not fit
  scrolls with the inset at the end of the scroll instead of painting into
  it. Left/right insets are padded.
- Palettes and Settings pad all four insets; their absolute corner buttons
  (Cancel, Done, back) add the side inset.
- App shell, shell sheets and onboarding pad the side insets; the drawer
  widens by the left inset; the note-list column pads left; side panels are
  inset (not padded, since core gives them their own side padding) and their
  Done button moves in; the toast host clears both sides.

Deliberately not done: no switch back to native padding on new WebViews (it
would also paint the status and navigation bar strips in the fixed dark
window color for every theme), no change to Immersive Mode, and no app-core
edit. App-core does not need one for this; optionally, `CloudConflictDialog`
could lay its panel out as `flex flex-col` with a `min-h-0 flex-1` body in
place of the fixed `max-h-[72vh]` so other hosts get the same fit.

## Verification

Throwaway AVD `zn-navbar-insets-37` (Pixel 8, Google APIs **API 37, WebView
145.0.7632.218**), debug APK, driven over the WebView's CDP socket. Density
480 (360x800 CSS px, a common phone size) unless noted. A stand-in for
Review sync changes was mounted with the exact core 2.75.0 Modal, Header and
Body class strings (six queued files, resolver still loading), since the AVD
has no Cloud account to raise a real conflict; the shell CSS lays it out as
it does the real dialog.

| Case | Before (1.1.45) | After |
| --- | --- | --- |
| Review sync changes, portrait, 3-button | last line at 757 to 841 px, bar from 752: under the buttons (Dave's screenshot) | body shrinks to end at 752; scrolled to the end the last line ends at 736 |
| Same, portrait, gesture | last line under the handle and off-screen | ends at 760, handle from 776 |
| Same, landscape 90 / 270, 3-button | "Finish later" under Back; body's last 21 px off-screen | every button and the last line inside the safe area, both rotations |
| Same, landscape, gesture | body's last line clipped by the screen edge | inside the safe area |
| Home and More sheet, landscape, 3-button | editor text, Read button and list rows under the side bar | probe finds nothing under any bar |
| Search palette, portrait | results scroller ends at 800, bar from 752 | ends at 752; with the keyboard up it still meets the keyboard (inset 0) |
| Settings, portrait | "Settings save automatically on this device." under the bar | footer ends at 751 |
| Drawer, side panel (Outline), landscape 270 | under the left bar | drawer rows start at 60 (inset 48); panel spans 48 to 756 |
| Trash confirm, Rename prompt | | unchanged layout; Rename sits directly on the keyboard |
| WebView 133 (API 36 AVD), portrait and landscape | | insets all 0 as before, app shell padding unchanged, conflict stand-in fully reachable |

The probe flags any button, link, status, input or text block whose
clipped box reaches into a non-zero inset. Before/after screenshots and the
two composites (`before-after-review-sync-portrait.png`,
`-landscape.png`) are in the side-session notes folder
`android-system-bar-insets/`, not in the repo.

Automated: `npm test` 308 passed (the two new layout contracts fail on the
`origin/main` CSS and pass here); `npm run typecheck` clean;
`npm run build` and `./gradlew assembleDebug` succeed.

Not verified: a real Cloud conflict on a real device (no account on the
AVD), WebView 140 to 143 (the AVD images ship 133 or 145), API 29 with a
modern WebView (`neutralizeDoubleKeyboardInset` path, untouched here), and
TalkBack.

## How to test locally

1. `npm ci && npm run build && npx cap sync android && (cd android && ./gradlew assembleDebug)`.
2. Boot an emulator whose WebView is 140 or newer (the API 37 Google APIs
   image ships 145), then:
   `adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton`
   and, for a phone-sized viewport, `adb shell wm density 480`.
3. `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`, launch,
   "Get started", "Start writing".
4. Portrait: open the ensō, Search, dismiss the keyboard: the results end at
   the top of the bar (before: under it). More, Settings: the footer note sits
   above the bar.
5. Landscape (`adb shell settings put system user_rotation 1`): the editor
   text, the More sheet and the Cloud footer stay clear of the right-hand
   buttons; `user_rotation 3` moves the bar left, and the drawer (Browse)
   clears it.
6. With a Cloud account and two devices, edit one note on both and sync:
   Review sync changes keeps its last line and buttons above the bar,
   portrait and landscape.
