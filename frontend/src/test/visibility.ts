/**
 * Lo que esconde un nodo a la vista, como selector para `closest`: los tests no cargan CSS, así
 * que las clases se miran por nombre, con sus variantes por ancho (desde los dos puntos, para no
 * nombrar utilidades que el CSS no usa). Un solo lugar, para que la lista y el detalle no diverjan.
 */
export const HIDDEN_FROM_VIEW = [
  '.sr-only',
  '.hidden',
  '.invisible',
  '.opacity-0',
  '[class*=":sr-only"]',
  '[class*=":hidden"]',
  '[class*=":invisible"]',
  '[class*=":opacity-0"]',
  '[class*="scale-"]',
].join(', ')

/** Lo que el lector de pantalla oye como imagen con nombre: un svg sin ocultar, un img o un rol. */
export const NAMED_IMAGE = 'svg:not([aria-hidden="true"]), img, [role="img"]'
