import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { applyTheme, resolveTheme, themeStorage, type Theme } from './themeStorage'

interface ThemeContextValue {
  /** El tema que se está viendo, ya resuelto: nunca es `null`. */
  theme: Theme
  /** `true` cuando el usuario eligió a mano y por lo tanto el sistema dejó de mandar. */
  elegido: boolean
  toggleTheme: () => void
}

const ThemeContext = createContext<ThemeContextValue | null>(null)

/**
 * Vive bajo `shared/ui` y NO en `shared/theme`, que es la carpeta de al lado con el mismo
 * nombre conceptual. La diferencia importa: `shared/theme` guarda las herramientas que MIDEN
 * el tema (compilar el CSS, leer la paleta, calcular contraste), corren solo en Node, y está
 * excluida del escaneo de Tailwind a propósito; lo que se pusiera ahí no publicaría sus
 * clases. Esto, en cambio, es código de pantalla. Una prueba de esa carpeta afirma que nadie
 * la importe desde la aplicación, y tiene su motivo medido: uno de sus módulos arrastra
 * Tailwind entero al bundle.
 *
 * El tema de la aplicación, con la precedencia de la sección 5.2 del documento de diseño:
 * la elección del usuario manda sobre la preferencia del sistema, y esa sobre el claro.
 *
 * El estado arranca ya resuelto y NO desde un valor por omisión, porque el atributo ya lo
 * escribió el script del `<head>` antes de que React montara: si acá se empezara en claro y
 * se corrigiera en un efecto, habría un destello en cada carga, que es exactamente lo que
 * ese script existe para evitar.
 *
 * Mientras el usuario no haya elegido, se escucha al sistema: si el sistema pasa a oscuro de
 * noche, la aplicación acompaña. Después de una elección manual se deja de escuchar, porque
 * una elección explícita que el sistema pisa media hora después se lee como un error.
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<Theme>(() => resolveTheme())
  const [elegido, setElegido] = useState<boolean>(() => themeStorage.get() !== null)

  useEffect(() => {
    applyTheme(theme)
  }, [theme])

  useEffect(() => {
    if (elegido || typeof window.matchMedia !== 'function') return
    const consulta = window.matchMedia('(prefers-color-scheme: dark)')
    const alCambiar = (evento: MediaQueryListEvent) => setTheme(evento.matches ? 'dark' : 'light')
    consulta.addEventListener('change', alCambiar)
    return () => consulta.removeEventListener('change', alCambiar)
  }, [elegido])

  const toggleTheme = useCallback(() => {
    // El guardado va ACÁ y no adentro del updater de `setTheme`: el updater corre durante el
    // render y bajo `StrictMode` React lo invoca dos veces, así que un efecto ahí adentro se
    // ejecuta dos veces y una excepción suya rompe el render en vez del manejador.
    const siguiente: Theme = theme === 'dark' ? 'light' : 'dark'
    themeStorage.set(siguiente)
    setTheme(siguiente)
    setElegido(true)
  }, [theme])

  const valor = useMemo(() => ({ theme, elegido, toggleTheme }), [theme, elegido, toggleTheme])
  return <ThemeContext.Provider value={valor}>{children}</ThemeContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useTheme(): ThemeContextValue {
  const contexto = useContext(ThemeContext)
  if (!contexto) {
    throw new Error('useTheme se usó fuera de ThemeProvider')
  }
  return contexto
}
