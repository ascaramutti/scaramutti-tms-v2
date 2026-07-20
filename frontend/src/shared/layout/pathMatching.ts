/**
 * Prefix-matching por segmento: /clientes/123 marca activo a /clientes, pero
 * /clientesX no. La raíz `/` matchea solo exacta (si no, marcaría todo).
 *
 * Lo usan tanto el resaltado default de los items del menú como los matchers
 * custom (`activeWhen`) de los módulos que comparten prefijo entre sí.
 */
export function matchesPathPrefix(pathname: string, prefix: string): boolean {
  if (prefix === '/') return pathname === '/'
  return pathname === prefix || pathname.startsWith(`${prefix}/`)
}
