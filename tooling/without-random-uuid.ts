/**
 * Runs `work` the way a WebView without crypto.randomUUID would (iOS 15.0 to
 * 15.3): crypto keeps getRandomValues but has no randomUUID. The real method
 * comes back after.
 */
export async function withoutRandomUUID<T>(work: () => Promise<T> | T): Promise<T> {
  const webCrypto = globalThis.crypto
  Object.defineProperty(webCrypto, 'randomUUID', { value: undefined, configurable: true, writable: true })
  try {
    return await work()
  } finally {
    Reflect.deleteProperty(webCrypto, 'randomUUID')
  }
}
