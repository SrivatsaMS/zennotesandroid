import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { systemBarThemeFor } from './system-bar-theme-color.ts'

// `--z-bg` of every built-in theme in core 2.78.0 (dist/styles/index.css).
const DARK = {
  'apple-dark': '28 28 30',
  'dark-hard': '29 32 33',
  'dark-medium': '40 40 40',
  'dark-soft': '50 48 47',
  'catppuccin-frappe': '48 52 70',
  'catppuccin-macchiato': '36 39 58',
  'catppuccin-mocha': '30 30 46',
  'rose-pine-main': '25 23 36',
  'rose-pine-moon': '35 33 54',
  'github-dark': '13 17 23',
  'github-dark-dimmed': '34 39 46',
  'github-dark-high-contrast': '0 0 0',
  'solarized-dark': '0 43 54',
  'one-dark': '40 44 52',
  'nord-dark': '46 52 64',
  'tokyo-night-storm': '36 40 59',
  'kanagawa-wave': '31 31 40',
  'kanagawa-dragon': '24 22 22',
  'kanagawa-paper-ink': '20 19 27',
  'black-metal': '0 0 0'
}
const LIGHT = {
  'apple-light': '255 255 255',
  'light-hard': '249 245 215',
  'light-medium': '251 241 199',
  'light-soft': '242 229 188',
  'catppuccin-latte': '239 241 245',
  'rose-pine-dawn': '250 244 237',
  'github-light': '255 255 255',
  'solarized-light': '253 246 227',
  'one-light': '250 250 250',
  'nord-light': '236 239 244',
  'tokyo-night-day': '225 226 231',
  'kanagawa-lotus': '242 236 188',
  'black-metal-day': '244 244 242'
}

describe('systemBarThemeFor', () => {
  it('gives every dark built-in theme light icons', () => {
    for (const [id, bg] of Object.entries(DARK)) {
      assert.equal(systemBarThemeFor(bg)?.light, false, id)
    }
  })

  it('gives every light built-in theme dark icons', () => {
    for (const [id, bg] of Object.entries(LIGHT)) {
      assert.equal(systemBarThemeFor(bg)?.light, true, id)
    }
  })

  it('hands the native side the background as #rrggbb', () => {
    assert.deepEqual(systemBarThemeFor('29 32 33'), { color: '#1d2021', light: false })
    assert.deepEqual(systemBarThemeFor(' 251 241 199 '), { color: '#fbf1c7', light: true })
  })

  it('picks the icon color with more contrast around the middle', () => {
    // Relative luminance about 0.16 and 0.20, either side of the tie.
    assert.equal(systemBarThemeFor('112 112 112')?.light, false)
    assert.equal(systemBarThemeFor('124 124 124')?.light, true)
  })

  it('ignores anything that is not three channels', () => {
    for (const raw of ['', '29 32', '29 32 33 1', 'rgb(29, 32, 33)', '#1d2021', '29 32 256', '29.5 32 33']) {
      assert.equal(systemBarThemeFor(raw), null, raw)
    }
  })
})
