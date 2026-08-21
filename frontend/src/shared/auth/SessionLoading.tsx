import { Spinner } from '../ui/Spinner'

/**
 * Espera mientras se resuelve la sesión.
 *
 * Lo usan los dos puntos que necesitan saber quién es el usuario antes de
 * decidir a dónde mandarlo: la guarda de rutas y el destino de las rutas que no
 * existen. El segundo se monta FUERA del layout, así que este componente trae
 * su propio centrado: si no, el texto sale pegado a la esquina de una pantalla
 * en blanco.
 *
 * El `role="status"` con `aria-live` lo aporta el Spinner. Un texto suelto con
 * el rol puesto desde el primer render no se anuncia: una región viva solo avisa
 * de los cambios posteriores a su montaje.
 */
export function SessionLoading() {
  return (
    <div className="flex min-h-[50vh] items-center justify-center gap-3 text-slate-500">
      <Spinner label="Cargando sesión" />
      <span className="text-sm">Cargando sesión…</span>
    </div>
  )
}
