import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ThemeProvider, useTheme } from './ThemeContext'
import { ThemedToaster } from './ThemedToaster'

/**
 * `sonner` pinta sus avisos con su propia paleta y no mira el atributo del documento: si no se
 * le pasa el tema, sobre una aplicación oscura salen claros. La contención es una línea, y por
 * eso mismo se borra sin que nadie la extrañe: la revisión midió que reemplazar este componente
 * por el `Toaster` pelado dejaba la suite entera en verde.
 *
 * Se espía la librería en vez de mirar el DOM porque el tema de `sonner` no se puede leer desde
 * afuera de forma estable: lo que importa es qué recibe, y eso es exactamente lo que se afirma.
 */
const recibido: Array<Record<string, unknown>> = []
vi.mock('sonner', () => ({
  Toaster: (props: Record<string, unknown>) => {
    recibido.push(props)
    return <div data-testid="toaster" />
  },
}))

/** Un botón que alterna el tema, para ver que el aviso lo sigue y no se queda con el de arranque. */
function Interruptor() {
  const { toggleTheme } = useTheme()
  return (
    <button type="button" onClick={toggleTheme}>
      cambiar
    </button>
  )
}

afterEach(() => {
  recibido.length = 0
  vi.unstubAllGlobals()
})

describe('las notificaciones siguen al tema', () => {
  function montar() {
    return render(
      <ThemeProvider>
        <Interruptor />
        <ThemedToaster />
      </ThemeProvider>,
    )
  }

  it('arranca con el tema resuelto', () => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({ matches: false, addEventListener: () => {}, removeEventListener: () => {} })),
    )
    montar()
    expect(screen.getByTestId('toaster')).toBeInTheDocument()
    expect(recibido.at(-1)?.theme, 'sin esto el aviso sale con la paleta de la librería').toBe(
      'light',
    )
  })

  it('cambia cuando el usuario cambia el tema', async () => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn(() => ({ matches: false, addEventListener: () => {}, removeEventListener: () => {} })),
    )
    montar()
    await userEvent.click(screen.getByRole('button', { name: 'cambiar' }))
    expect(recibido.at(-1)?.theme).toBe('dark')
  })
})
