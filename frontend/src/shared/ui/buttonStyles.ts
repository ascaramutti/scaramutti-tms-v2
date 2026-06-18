/**
 * Estilos de botón compartidos (clases Tailwind). El proyecto no tiene un componente
 * `Button` propio; estas constantes evitan duplicar las clases donde se necesitan
 * botones de acción (ej. Detalle de cotización + sus acciones de PDF).
 */
export const PRIMARY_BUTTON =
  'inline-flex items-center rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2'

export const SECONDARY_BUTTON =
  'inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500'

/**
 * Botón de acción destructiva (rojo): rechazar, eliminar, etc. Mismo layout que
 * PRIMARY/SECONDARY; focus-ring rojo coherente con el resto de la familia.
 */
export const DANGER_BUTTON =
  'inline-flex items-center rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-red-500 focus:ring-offset-2'
