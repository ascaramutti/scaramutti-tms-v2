import '@testing-library/jest-dom/vitest'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { cleanup } from '@testing-library/react'
import { server } from './mocks/server'

// Polyfill de Storage en memoria.
// Node 22+ expone `globalThis.localStorage` como un getter sin metodos cuando
// se ejecuta sin `--localstorage-file`, lo cual pisa al de happy-dom/jsdom.
// Forzamos el polyfill antes de cualquier test para que el codigo que
// use window.localStorage funcione en el entorno de testing.
class MemoryStorage implements Storage {
  private store = new Map<string, string>()
  get length(): number { return this.store.size }
  clear(): void { this.store.clear() }
  getItem(key: string): string | null { return this.store.get(key) ?? null }
  key(index: number): string | null { return Array.from(this.store.keys())[index] ?? null }
  removeItem(key: string): void { this.store.delete(key) }
  setItem(key: string, value: string): void { this.store.set(key, value) }
}

const memoryLocalStorage = new MemoryStorage()
const memorySessionStorage = new MemoryStorage()

Object.defineProperty(globalThis, 'localStorage', {
  get: () => memoryLocalStorage,
  configurable: true,
})
Object.defineProperty(globalThis, 'sessionStorage', {
  get: () => memorySessionStorage,
  configurable: true,
})
if (typeof window !== 'undefined') {
  Object.defineProperty(window, 'localStorage', {
    get: () => memoryLocalStorage,
    configurable: true,
  })
  Object.defineProperty(window, 'sessionStorage', {
    get: () => memorySessionStorage,
    configurable: true,
  })
}

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  memoryLocalStorage.clear()
  memorySessionStorage.clear()
})
afterAll(() => server.close())
