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
 * Ahora la sesión decide. Cuando entra por el comodín y no hay sesión, la ruta
 * rota no se guarda como destino de retorno: volver a una URL que no existe solo
 * repite el rebote después de iniciar sesión. Por el otro camino, el de un id que
 * no es válido, quien corta antes es la guarda del layout, y esa sí guarda el
 * destino: después del login el desvío vuelve a correr y termina igual en la
 * principal del rol.
 *
 * Alcance: llegan las rutas que no matchean nada, y también las que caen en el
 * detalle de cotización con un id que no es un entero positivo, porque
 * `RequireNumericId` las desvía acá antes de la guarda de rol. Así una URL vieja
 * de un solo segmento termina en la principal del rol y no en "Sin acceso".
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
