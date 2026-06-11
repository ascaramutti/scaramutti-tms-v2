const ACCESS_TOKEN_KEY = 'tms.accessToken'
const REFRESH_TOKEN_KEY = 'tms.refreshToken'

// Usamos `window.localStorage` explicito para esquivar el conflicto de
// `globalThis.localStorage` en Node 22+ (visible en tests).
export const tokenStorage = {
  getAccessToken(): string | null {
    return window.localStorage.getItem(ACCESS_TOKEN_KEY)
  },
  getRefreshToken(): string | null {
    return window.localStorage.getItem(REFRESH_TOKEN_KEY)
  },
  setTokens(accessToken: string, refreshToken: string | null): void {
    window.localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
    if (refreshToken) {
      window.localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
    } else {
      window.localStorage.removeItem(REFRESH_TOKEN_KEY)
    }
  },
  clear(): void {
    window.localStorage.removeItem(ACCESS_TOKEN_KEY)
    window.localStorage.removeItem(REFRESH_TOKEN_KEY)
  },
}
