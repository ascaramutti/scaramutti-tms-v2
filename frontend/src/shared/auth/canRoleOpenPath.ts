import type { UserRole } from '../../api'
import { OPERATIONS_BASE, QUOTATIONS_BASE, WAREHOUSE_BASE } from '../paths'
import { matchesPathPrefix } from '../layout/pathMatching'
import {
  OPERATIONS_ROLES,
  QUOTATION_ROLES,
  SERVICE_PRICE_WRITE_ROLES,
  WAREHOUSE_ROLES,
} from './moduleRoles'

/**
 * Si un rol puede abrir una ruta de la aplicación.
 *
 * Existe para una sola decisión: cuando alguien llega al login por un enlace
 * directo, el destino guardado gana, salvo que su rol no pueda verlo. Sin esta
 * pregunta, un despachador que abre un enlace a una cotización aterriza en la
 * pantalla de "Sin acceso", que es un error de permisos donde debería haber una
 * pantalla de trabajo.
 *
 * Lee las mismas listas que el router, y por eso vive al lado de ellas: dos
 * fuentes de verdad sobre quién entra a dónde se desincronizan sin que nada
 * avise, y el síntoma sería exactamente el que esto viene a arreglar.
 */
const POR_RUTA: ReadonlyArray<readonly [string, UserRole[]]> = [
  // Dentro de operaciones hay dos pantallas que el despachador no abre: alta y
  // edición de un servicio, que tocan el precio. Van antes que su módulo porque
  // gana la primera coincidencia; sin ellas, un enlace a "registrar un servicio"
  // lo dejaría en "No puedes registrar un servicio", que es el mismo error de
  // permisos que esto viene a evitar, una pantalla más adentro.
  [`${OPERATIONS_BASE}/servicios/nuevo`, SERVICE_PRICE_WRITE_ROLES],
  [`${OPERATIONS_BASE}/servicios/:id/editar`, SERVICE_PRICE_WRITE_ROLES],
  [QUOTATIONS_BASE, QUOTATION_ROLES],
  [WAREHOUSE_BASE, WAREHOUSE_ROLES],
  [OPERATIONS_BASE, OPERATIONS_ROLES],
]

/** Si una ruta concreta encaja en un patrón del router, con `:id` como comodín. */
function encaja(pathname: string, patron: string): boolean {
  if (!patron.includes(':')) return matchesPathPrefix(pathname, patron)
  const regex = new RegExp('^' + patron.replace(/:[^/]+/g, '[^/]+') + '$')
  return regex.test(pathname)
}

/**
 * Una ruta de esta aplicación: absoluta, del propio dominio.
 *
 * Descarta la relativa al protocolo y cualquier cosa con esquema (`https:`,
 * `javascript:`). Una barra seguida de barra invertida cuenta igual que dos
 * barras: los navegadores la normalizan, y react-router aborta con "External
 * navigation is not allowed", que sin `errorElement` deja la pantalla en blanco.
 *
 * Hoy el destino guardado solo lo escribe la guarda de ruta desde el `pathname`
 * del navegador, así que no hay por dónde entrar algo así; queda medido de todos
 * modos, porque el día que ese destino venga de un parámetro de la URL esto es lo
 * único que separa un redirect de la aplicación de uno a cualquier lado.
 */
function esRutaDeLaApp(pathname: string): boolean {
  return pathname.startsWith('/') && !/^\/[\\/]/.test(pathname)
}

export function canRoleOpenPath(pathname: string, role: UserRole | undefined): boolean {
  if (!role) return false
  if (!esRutaDeLaApp(pathname)) return false
  const regla = POR_RUTA.find(([patron]) => encaja(pathname, patron))
  // Fuera de los tres módulos no hay lista que consultar: el login, la cuenta y
  // cualquier ruta que no exista las resuelve el router, que ya manda a cada rol
  // a donde corresponde. Decir que no acá mandaría a la principal una ruta que el
  // usuario sí podía abrir.
  if (!regla) return true
  return regla[1].includes(role)
}
