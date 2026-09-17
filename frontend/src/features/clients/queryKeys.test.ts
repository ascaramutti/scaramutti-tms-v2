import { describe, expect, it } from 'vitest'
import { clientKeys } from './queryKeys'

/**
 * Las claves de caché son un contrato entre pantallas que no se conocen: el
 * maestro de clientes invalida las búsquedas, y de esa invalidación dependen los
 * combobox de alta al vuelo del asistente de cotizaciones y del alta de
 * servicios.
 *
 * Por eso se afirman los literales y no el resultado de las mismas funciones que
 * los producen: una aserción como `search(p)` contra `searches()` pasa igual si
 * las dos cambian juntas, que es exactamente el día que la invalidación deja de
 * alcanzar a nadie.
 */
describe('clientKeys', () => {
  it('la raíz y las búsquedas son las cadenas que el resto del árbol espera', () => {
    expect(clientKeys.all).toEqual(['clients'])
    expect(clientKeys.searches()).toEqual(['clients', 'search'])
  })

  it('una búsqueda cuelga de la rama de búsquedas', () => {
    // Si deja de colgar, invalidar las búsquedas no alcanza a ninguna y los
    // combobox siguen ofreciendo la razón social vieja hasta recargar.
    expect(clientKeys.search({ q: 'acme' })).toEqual(['clients', 'search', { q: 'acme' }])
  })

  it('el filtro de activos distingue una búsqueda de otra', () => {
    // Sin esto, el maestro (que filtra) y los combobox (que no) comparten entrada
    // de caché: el desplegable empezaría a no encontrar clientes desactivados.
    expect(clientKeys.search({ q: 'acme' })).not.toEqual(
      clientKeys.search({ q: 'acme', isActive: true }),
    )
  })

  it('el detalle cuelga de la raíz y distingue por id', () => {
    expect(clientKeys.detail(7)).toEqual(['clients', 'detail', 7])
    expect(clientKeys.detail(7)).not.toEqual(clientKeys.detail(8))
  })
})
