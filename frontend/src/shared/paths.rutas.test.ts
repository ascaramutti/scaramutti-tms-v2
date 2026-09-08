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
 * En la centralización prueba que ninguna URL se movió. En la mudanza que sigue
 * se actualiza A PROPÓSITO, y ese diff es el que hay que leer con atención: es
 * la lista completa de lo que cambia para el usuario.
 */
const RUTAS = [
  '/cotizaciones/login',
  '/cotizaciones',
  '/cotizaciones/nueva',
  '/cotizaciones/:id/editar',
  '/cotizaciones/:id',
  '/cotizaciones/almacen',
  '/cotizaciones/almacen/entradas/nueva',
  '/cotizaciones/almacen/entradas',
  '/cotizaciones/almacen/entradas/:id/editar',
  '/cotizaciones/almacen/entradas/:id',
  '/cotizaciones/almacen/retiros/nuevo',
  '/cotizaciones/almacen/retiros',
  '/cotizaciones/almacen/retiros/:id/editar',
  '/cotizaciones/almacen/retiros/:id',
  '/cotizaciones/almacen/reportes',
  '/cotizaciones/almacen/corte-inicial',
  '/cotizaciones/almacen/productos/:id',
  '/cotizaciones/operaciones',
  '/cotizaciones/operaciones/servicios/nuevo',
  '/cotizaciones/operaciones/servicios/:id/editar',
  '/cotizaciones/operaciones/servicios/:id',
  '/cotizaciones/cuenta/cambiar-contrasena',
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
