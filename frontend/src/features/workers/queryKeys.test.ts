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
})
