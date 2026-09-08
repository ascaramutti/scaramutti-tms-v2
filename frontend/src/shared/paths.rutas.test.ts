import { describe, expect, it } from 'vitest'
import type { RouteObject } from 'react-router-dom'
import { routes } from '../router'

/**
 * El contrato de URL de la aplicación, escrito a mano.
 *
 * Existe porque el resto de la suite ya no puede verlo: desde que los paths se
 * componen desde `shared/paths`, un test que arma la URL con la misma constante
 * que el router pasa igual aunque la constante cambie de valor. Esta lista es lo
 * único que fija los valores, y por eso está escrita entera y no derivada.
 *
 * En la centralización probó que ninguna URL se había movido. En la mudanza de
 * 2026-09 se actualizó A PROPÓSITO, y ese diff es la lista completa de lo que
 * cambió para el usuario: login, cuenta, almacén y operaciones salieron del
 * prefijo viejo, cotizaciones conservó sus URL y la raíz pasó a ser una ruta de
 * la aplicación en vez de una redirección de nginx.
 */
const RUTAS = [
  '/login',
  '/cotizaciones',
  '/cotizaciones/nueva',
  '/cotizaciones/:id/editar',
  '/cotizaciones/:id',
  '/almacen',
  '/almacen/entradas/nueva',
  '/almacen/entradas',
  '/almacen/entradas/:id/editar',
  '/almacen/entradas/:id',
  '/almacen/retiros/nuevo',
  '/almacen/retiros',
  '/almacen/retiros/:id/editar',
  '/almacen/retiros/:id',
  '/almacen/reportes',
  '/almacen/corte-inicial',
  '/almacen/productos/:id',
  '/operaciones',
  '/operaciones/servicios/nuevo',
  '/operaciones/servicios/:id/editar',
  '/operaciones/servicios/:id',
  '/cuenta/cambiar-contrasena',
  '/',
  '*',
]

/** Los paths de la tabla, en orden, con los hijos aplanados detrás de su padre. */
function aplanar(tabla: RouteObject[]): string[] {
  return tabla.flatMap((ruta) => [
    ...(ruta.path === undefined ? [] : [ruta.path]),
    ...(ruta.children ? aplanar(ruta.children) : []),
  ])
}

describe('las URL de la aplicación', () => {
  it('son exactamente estas', () => {
    expect(aplanar(routes)).toEqual(RUTAS)
  })

  it('el aplanado ve los hijos, no solo el primer nivel', () => {
    // Sin esto, una tabla que perdiera todo su árbol anidado pasaría el test de
    // arriba con una lista vacía y nadie se enteraría.
    expect(aplanar(routes).length).toBeGreaterThan(routes.length)
  })
})
