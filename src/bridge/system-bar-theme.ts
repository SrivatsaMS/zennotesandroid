/**
 * Keeps Android's status and navigation bar icons readable on the ZenNotes
 * theme: light icons on a dark theme, dark ones on a light theme, following
 * every theme switch while the app runs. The system's own light or dark mode
 * is not the question; the page under the icons is (SystemBarThemePlugin.java
 * has why neither Capacitor bar plugin gets this right).
 *
 * The theme is read where it ends up, app-core's `--z-bg` on the root, so a
 * built-in theme, Auto following the system, a custom CSS theme and a color
 * tweak all count. Those arrive as root attributes or as style elements in
 * the head, so both are watched, and the bars hear only real changes.
 * Android-only and best-effort, like EdgeSwipe.
 */
import { Capacitor, registerPlugin } from '@capacitor/core'
import { systemBarThemeFor, type SystemBarTheme } from './system-bar-theme-color'

interface SystemBarThemePlugin {
  setTheme(options: SystemBarTheme): Promise<void>
}

const NativeSystemBarTheme = registerPlugin<SystemBarThemePlugin>('SystemBarTheme')

let sent = ''

function sync(): void {
  const theme = systemBarThemeFor(
    getComputedStyle(document.documentElement).getPropertyValue('--z-bg')
  )
  if (!theme) return
  const key = `${theme.color}:${theme.light}`
  if (key === sent) return
  sent = key
  void NativeSystemBarTheme.setTheme(theme).catch(() => {
    sent = ''
  })
}

export function installSystemBarTheme(): void {
  if (Capacitor.getPlatform() !== 'android') return
  let queued = false
  const schedule = (): void => {
    if (queued) return
    queued = true
    requestAnimationFrame(() => {
      queued = false
      sync()
    })
  }
  sync()
  const observer = new MutationObserver(schedule)
  observer.observe(document.documentElement, { attributes: true })
  observer.observe(document.head, { childList: true, subtree: true, characterData: true })
  // Auto mode flips the theme with the system; app-core's own listener sets
  // the attributes, but a frame later is cheap insurance.
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', schedule)
}
