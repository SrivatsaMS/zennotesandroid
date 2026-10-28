/**
 * The system bars' look for a ZenNotes theme: the theme's background, and
 * whether the bar icons go dark on it. Pure, so node --test covers it; the
 * wiring is in system-bar-theme.ts.
 */
export interface SystemBarTheme {
  /** `#rrggbb`: what the native side paints the bars' strips with. */
  color: string
  /** Dark icons (a light background) rather than light ones. */
  light: boolean
}

/** sRGB channel to linear light, as WCAG's relative luminance has it. */
function linear(channel: number): number {
  const c = channel / 255
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
}

/**
 * From app-core's `--z-bg` ("29 32 33": the channels, space separated).
 * Null for anything else, so a theme mid-swap never reaches the bars.
 *
 * The icons take whichever of white or black contrasts more with the
 * background. The two tie at a relative luminance of about 0.179, well away
 * from every built-in theme (dark ones sit under 0.06, light ones over 0.6).
 */
export function systemBarThemeFor(rawBackground: string): SystemBarTheme | null {
  const channels = rawBackground.trim().split(/\s+/).map(Number)
  if (
    channels.length !== 3 ||
    channels.some((channel) => !Number.isInteger(channel) || channel < 0 || channel > 255)
  ) {
    return null
  }
  const [r, g, b] = channels as [number, number, number]
  const luminance = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
  const contrastWithWhite = 1.05 / (luminance + 0.05)
  const contrastWithBlack = (luminance + 0.05) / 0.05
  const hex = channels.map((channel) => channel.toString(16).padStart(2, '0')).join('')
  return { color: `#${hex}`, light: contrastWithBlack > contrastWithWhite }
}
