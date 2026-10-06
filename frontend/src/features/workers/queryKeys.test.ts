import { describe, expect, it } from 'vitest'
import { workerKeys } from './queryKeys'

/**
 * Se afirman los literales y no el resultado de las mismas funciones: una
 * aserción de `search(p)` contra `searches()` pasa igual si las dos cambian
 * juntas, que es el día que invalidar las búsquedas deja de alcanzar a alguna.
 */
describe('workerKeys', () => {
  it('la raíz y las búsquedas son propias del padrón', () => {
    expect(workerKeys.all).toEqual(['workers'])
    expect(workerKeys.searches()).toEqual(['workers', 'search'])
  })

  it('una búsqueda cuelga de la rama de búsquedas', () => {
    expect(workerKeys.search({ q: 'ana', isActive: true })).toEqual([
      'workers',
      'search',
      { q: 'ana', isActive: true },
    ])
  })

  it('cada estado es una entrada distinta de caché', () => {
    // Si el estado no distingue, cambiar el filtro devolvería la lista anterior.
    const activos = workerKeys.search({ q: 'ana', isActive: true })
    const inactivos = workerKeys.search({ q: 'ana', isActive: false })
    const todos = workerKeys.search({ q: 'ana' })
    expect(activos).not.toEqual(inactivos)
    expect(activos).not.toEqual(todos)
    expect(inactivos).not.toEqual(todos)
  })

  /** Cuelga de la raíz y no de las búsquedas: invalidar la raíz la alcanza, invalidar búsquedas no. */
  it('el detalle cuelga de la raíz y distingue por id', () => {
    expect(workerKeys.detail(42)).toEqual(['workers', 'detail', 42])
    expect(workerKeys.detail(42).slice(0, 2)).not.toEqual(workerKeys.searches())
    expect(workerKeys.detail(7)).not.toEqual(workerKeys.detail(12))
  })

  /** Cuelgan de la raíz: el alta los marca viejos sin pedirlos (cambian solo por migración). */
  it('los catálogos del formulario cuelgan de la raíz', () => {
    expect(workerKeys.roles()).toEqual(['workers', 'roles'])
    expect(workerKeys.documentTypes()).toEqual(['workers', 'document-types'])
  })
})
