import { hashKey, type QueryClient } from '@tanstack/react-query'
import { LOGIN_PATH } from '../paths'
import { currentUserQueryKey } from './queryKeys'
import { tokenStorage } from './tokenStorage'

/**
 * Cuántas veces empezó o terminó una sesión en esta pestaña. Salir no recarga la página, así que lo
 * que la sesión dejó en vuelo vuelve igual: el cliente HTTP anota este número en cada pedido de la
 * sesión (no en el ingreso ni en la renovación) y no entrega la respuesta que vuelve con otro.
 */
let generation = 0
let endedSessions = 0

export function sessionGeneration(): number {
  return generation
}

/** Si en esta pestaña ya terminó una sesión: lo que quede de ella es del usuario anterior. */
export function hasSessionEndedHere(): boolean {
  return endedSessions > 0
}

/**
 * El ingreso también abre una generación: lo que quedó en vuelo de antes (una renovación con los
 * tokens viejos de la pestaña) no es de quien entra. Medido: sin esto, esa renovación lo echaba si
 * fallaba, y si salía bien le pisaba los tokens y lo dejaba en la sesión del otro usuario.
 */
export function beginSession(): void {
  generation += 1
}

/**
 * Lo que hacen salir y la sesión expirada: borrar todo lo que el usuario dejó en memoria, para que
 * quien entre después en esta pestaña no lo vea en pantalla ni pueda leerlo.
 */
export function endSession(queryClient: QueryClient): void {
  generation += 1
  endedSessions += 1
  tokenStorage.clear()
  // El usuario se pone en null y no se borra: borrar una query no le avisa al observer que la lee.
  // Medido: la pantalla seguía en el usuario anterior y el ingreso siguiente no llegaba a ella.
  queryClient.setQueryData(currentUserQueryKey, null)
  const currentUserHash = hashKey(currentUserQueryKey)
  // Borrar corta la espera y el reintento de cada query sin avisarle a su pantalla. Un
  // cancelQueries sí le avisa (revierte el estado): medido, la pantalla se redibujaba antes de
  // desmontarse y volvía a pedir sin token. La respuesta del pedido la descarta el cliente HTTP.
  queryClient.removeQueries({ predicate: (query) => query.queryHash !== currentUserHash })
  queryClient.getMutationCache().clear()
}

/**
 * La sesión expirada (la renovación falló o no se pudo intentar). Fuera del ingreso recarga, y la
 * recarga ya vacía la memoria; en el ingreso no recarga, y sin la rutina la memoria quedaba entera.
 */
export function expireSession(queryClient: QueryClient): void {
  endSession(queryClient)
  if (window.location.pathname !== LOGIN_PATH) {
    window.location.assign(LOGIN_PATH)
  }
}
