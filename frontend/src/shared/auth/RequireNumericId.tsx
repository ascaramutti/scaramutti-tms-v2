import type { ReactNode } from 'react'
import { useParams } from 'react-router-dom'
import { LandingRedirect } from './LandingRedirect'

/**
 * Deja pasar solo si el `:id` de la URL es un entero positivo; si no, manda al
 * aterrizaje por rol.
 *
 * Va POR ENCIMA de la guarda de rol, y ese orden es el punto. Desde que la SPA
 * se sirve en la raíz y las URL viejas no tienen redirección, una del prefijo
 * viejo con un solo segmento cae en el detalle de cotización con ese segmento de
 * id, y justamente las usan los roles que NO abren cotizaciones: un almacenero
 * parado en la vieja de almacén vería
 * "Sin acceso a Cotizaciones", un error de permisos donde corresponde su
 * pantalla de trabajo. Con la validación primero, cualquier URL vieja termina
 * en la principal del rol, que es lo que ya hacía cualquier URL inexistente.
 *
 * Compara contra la cadena y no contra `Number()`: `Number('1e2')` da 100, así
 * que un id que la aplicación nunca escribió pasaría por bueno.
 */
export function RequireNumericId({ children }: { children: ReactNode }) {
  const { id } = useParams<{ id: string }>()
  const esEnteroPositivo = id !== undefined && /^\d+$/.test(id) && Number(id) > 0
  if (!esEnteroPositivo) {
    return <LandingRedirect />
  }
  return <>{children}</>
}
