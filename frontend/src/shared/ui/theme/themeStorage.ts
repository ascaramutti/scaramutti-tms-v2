export type Theme = 'light' | 'dark'

const THEME_KEY = 'scaramutti.theme'

/** El atributo que el CSS mira. Se escribe una sola vez, acá y en el script del `<head>`. */
export const THEME_ATTRIBUTE = 'data-theme'

/**
 * La elección del usuario, guardada. Va por `window.localStorage` explícito y no por el
 * global, por el mismo motivo que el almacenamiento de la sesión: Node 22 expone
 * `globalThis.localStorage` como un getter sin métodos, y el setup de pruebas reemplaza el
 * de `window`. Leerlo del global haría que las pruebas midan otro almacenamiento.
 *
 * Devuelve `null` cuando el usuario NO eligió, que no es lo mismo que haber elegido claro:
 * sin elección manda la preferencia del sistema, y eso solo se puede distinguir si la
 * ausencia tiene su propio valor.
 */
export const themeStorage = {
  get(): Theme | null {
    try {
      const guardado = window.localStorage.getItem(THEME_KEY)
      return guardado === 'light' || guardado === 'dark' ? guardado : null
    } catch {
      return null
    }
  },
  set(theme: Theme): void {
    try {
      window.localStorage.setItem(THEME_KEY, theme)
    } catch {
      // Sin dónde guardar, la elección vale para esta sesión y se pierde al recargar. Es
      // mejor que romper el clic: el tema es una preferencia, no un dato.
    }
  },
  // No hay un `clear`, y es una decisión y no un olvido: el almacenamiento de la sesión tiene
  // uno porque cerrar sesión lo llama, y acá no hay a quién. El interruptor define dos estados
  // y no tres, así que no existe un "volvé a seguir al sistema" que apretar. Un método sin
  // llamador es superficie que alguien va a usar mal antes de que se decida el tercer estado.
}

/** La preferencia del sistema operativo, o `null` si el navegador no sabe responder. */
export function systemTheme(): Theme | null {
  if (typeof window.matchMedia !== 'function') return null
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

/**
 * La precedencia, en un solo lugar: lo que el usuario eligió, si no lo que dice el sistema,
 * si no claro. El script del `<head>` repite esta misma cadena en JavaScript plano porque
 * corre antes de que exista un módulo; si una de las dos cambia, cambian las dos.
 */
export function resolveTheme(): Theme {
  return themeStorage.get() ?? systemTheme() ?? 'light'
}

/**
 * Escribe el atributo en el documento. Es lo único que hace visible un cambio de tema.
 *
 * Y de paso el color de la barra del navegador en el móvil, que vive fuera de la página y no lo
 * alcanza ninguna hoja de estilos. El valor NO se escribe a mano acá: se lee del token del fondo
 * de página ya resuelto, así que cambiar el tema en el CSS lo cambia también en la barra. El
 * script del `head` sí lo lleva escrito, porque corre antes de que exista un módulo, y hay una
 * prueba que ata esos dos literales a estos tokens.
 */
export function applyTheme(theme: Theme): void {
  document.documentElement.setAttribute(THEME_ATTRIBUTE, theme)
  const barra = document.querySelector('meta[name="theme-color"]')
  if (!barra) return
  const fondo = getComputedStyle(document.documentElement).getPropertyValue('--color-canvas').trim()
  if (fondo) barra.setAttribute('content', fondo)
}
