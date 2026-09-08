import { LOGIN_PATH } from '../../shared/paths'
import { Navigate } from 'react-router-dom'
import { SessionLoading } from './SessionLoading'
import { useAuth } from './AuthContext'
import { landingPathFor } from './roleLanding'

/**
 * Destino de cualquier ruta que no existe (el catch-all del router).
 *
 * Antes mandaba a todos al módulo comercial fijo, que solo sirve para los roles
 * del módulo comercial: al despachador y a los dos de almacén, un simple typo
 * en la URL les mostraba "Sin acceso a Cotizaciones", un error de permisos que
 * no tiene nada que ver con lo que pasó.
 *
 * Ahora la sesión decide. Y sin sesión no se guarda la ruta rota como destino
 * de retorno: volver a una URL que no existe solo repite el rebote después de
 * iniciar sesión.
 *
 * ⚠️ Alcance: acá llegan solo las rutas de DOS O MÁS segmentos que no matchean
 * nada. Un solo segmento después de la base del módulo comercial no llega: lo captura
 * el detalle por id y lo
 * trata como id de cotización, con la guarda del módulo comercial. O sea que el
 * typo de un solo segmento sigue mostrando "Sin acceso" a quien no trabaja ahí,
 * y en ese camino el destino de retorno sí se guarda.
 *
 * Es un redirect silencioso a propósito: la aplicación es chica y el caso
 * típico es un error de tipeo, no un enlace roto que haya que investigar.
 */
export function LandingRedirect() {
  const { isAuthenticated, isLoading, user } = useAuth()

  // Sin esperar a que resuelva la sesión, un usuario con sesión válida sería
  // mandado al login solo por lo que tarda la consulta.
  if (isLoading) {
    return <SessionLoading />
  }

  if (!isAuthenticated) {
    return <Navigate to={LOGIN_PATH} replace />
  }

  return <Navigate to={landingPathFor(user?.role)} replace />
}
