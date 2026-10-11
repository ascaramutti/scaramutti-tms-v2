import type { SessionUser } from '../../api'

/**
 * Si la ficha es la de quien inició sesión. Se compara con el trabajador de la sesión y no con su
 * cuenta: son ids de tablas distintas, que pueden coincidir en número. Un backend anterior no manda
 * el dato, y entonces ninguna ficha es la propia: la pantalla queda como antes.
 */
export function isOwnRecord(workerId: number, user: SessionUser | null): boolean {
  return user?.workerId === workerId
}
