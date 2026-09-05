import { Toaster } from 'sonner'
import { useTheme } from './ThemeContext'

/**
 * Las notificaciones, con el tema activo.
 *
 * `sonner` trae su propio tema y NO mira el atributo del documento: sin pasárselo, sus avisos
 * salen con la paleta clara sobre una aplicación oscura. El documento de diseño ya lo tenía
 * anotado como riesgo, con esta misma contención, y la primera versión de este PR no la
 * aplicó; lo levantó la revisión.
 *
 * Vive en su propio archivo y no en el arranque porque `useTheme` solo se puede llamar DENTRO
 * del proveedor, y el arranque es quien lo monta.
 */
export function ThemedToaster() {
  const { theme } = useTheme()
  return <Toaster richColors position="top-right" theme={theme} />
}
