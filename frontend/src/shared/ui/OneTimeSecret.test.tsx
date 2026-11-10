import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { axe } from 'vitest-axe'
import { hidePageAndReadText } from '../../test/pagehide'
import { OneTimeSecret } from './OneTimeSecret'

const SECRETO = 'AbCdEfGhJkMn'
const YA_NO = 'Ya no se muestra. Si no la copiaste, restablécela desde el detalle de la cuenta.'
const AVISO = 'No se vuelve a mostrar. Cópiala ahora y entrégala a la persona en privado; se escribe sin espacios.'

function renderSecreto(value: string | null = SECRETO, onExpire = vi.fn(), groupSize?: number) {
  const vista = render(<OneTimeSecret label="Contraseña temporal" value={value} onExpire={onExpire} groupSize={groupSize} />)
  return { ...vista, onExpire }
}

/** Después de `userEvent.setup()`, que pone su propio portapapeles. */
function conPortapapeles(writeText?: (text: string) => Promise<void>) {
  Object.defineProperty(navigator, 'clipboard', {
    value: writeText ? { writeText: vi.fn(writeText) } : undefined,
    configurable: true,
  })
  return navigator.clipboard?.writeText as ReturnType<typeof vi.fn> | undefined
}

function conExecCommand(resultado: boolean) {
  const execCommand = vi.fn(() => resultado)
  Object.defineProperty(document, 'execCommand', { value: execCommand, configurable: true, writable: true })
  return execCommand
}

const copiar = () => screen.getByRole('button', { name: /^Cop/ })
const estado = () => screen.getByRole('status')
const seleccion = () => window.getSelection()?.toString()

afterEach(() => {
  delete (document as { execCommand?: unknown }).execCommand
  window.getSelection()?.removeAllRanges()
  vi.useRealTimers()
})

describe('OneTimeSecret', () => {
  it('muestra el rótulo, el valor, Copiar y el aviso de que no se vuelve a mostrar', () => {
    renderSecreto()
    expect(screen.getByText('Contraseña temporal')).toBeInTheDocument()
    expect(screen.getByText('AbCd').parentElement).toHaveTextContent(SECRETO)
    expect(copiar()).toHaveAccessibleName('Copiar')
    expect(copiar()).toHaveAccessibleDescription('Contraseña temporal')
    expect(screen.getByText(AVISO)).toBeInTheDocument()
    expect(estado()).toHaveTextContent(/^$/)
  })

  it('el valor va en monoespaciada en tres grupos de cuatro, sin espacios en el texto', () => {
    renderSecreto()
    const valor = screen.getByText('AbCd').parentElement as HTMLElement
    expect(valor.className.split(/\s+/)).toContain('font-mono')
    expect(valor.className.split(/\s+/)).not.toContain('flex')
    expect(valor.className.split(/\s+/)).toContain('space-x-2')
    expect(valor.textContent).not.toMatch(/\s/)
    expect(Array.from(valor.children).map((grupo) => grupo.textContent)).toEqual(['AbCd', 'EfGh', 'JkMn'])
    expect(valor.textContent).toBe(SECRETO)
  })

  it('agrupa por el tamaño que se le pide', () => {
    renderSecreto(SECRETO, vi.fn(), 3)
    const valor = screen.getByText('AbC').parentElement as HTMLElement
    expect(Array.from(valor.children).map((grupo) => grupo.textContent)).toEqual(['AbC', 'dEf', 'GhJ', 'kMn'])
  })

  it('el aviso y el "ya no se muestra" se cambian por props', () => {
    const textos = { notice: 'Entrégala hoy.', expiredNotice: 'Ya no está.' }
    const { rerender } = render(<OneTimeSecret label="Clave" value={SECRETO} onExpire={() => {}} {...textos} />)
    expect(screen.getByText('Entrégala hoy.')).toBeInTheDocument()
    expect(screen.queryByText(AVISO)).not.toBeInTheDocument()
    rerender(<OneTimeSecret label="Clave" value={null} onExpire={() => {}} {...textos} />)
    expect(screen.getByText('Ya no está.')).toBeInTheDocument()
    expect(screen.queryByText(YA_NO)).not.toBeInTheDocument()
  })

  it('el valor es texto, no un campo', () => {
    renderSecreto()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(document.querySelector('input')).toBeNull()
  })

  it('Copiar escribe el valor sin espacios en el portapapeles y lo anuncia', async () => {
    const user = userEvent.setup()
    const writeText = conPortapapeles(() => Promise.resolve())
    const execCommand = conExecCommand(true)
    renderSecreto()
    await user.click(copiar())
    expect(writeText).toHaveBeenCalledWith(SECRETO)
    expect(execCommand).not.toHaveBeenCalled()
    expect(copiar()).toHaveAccessibleName('Copiada')
    expect(estado()).toHaveTextContent('Copiada al portapapeles.')
  })

  it('"Copiada" dura unos segundos y vuelve a "Copiar"', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    conPortapapeles(() => Promise.resolve())
    renderSecreto()
    await user.click(copiar())
    expect(copiar()).toHaveAccessibleName('Copiada')
    await act(() => vi.advanceTimersByTimeAsync(2000))
    expect(copiar()).toHaveAccessibleName('Copiada')
    await act(() => vi.advanceTimersByTimeAsync(1000))
    expect(copiar()).toHaveAccessibleName('Copiar')
    expect(estado()).toHaveTextContent(/^$/)
  })

  it.each([
    ['sin portapapeles', undefined],
    ['con el portapapeles que rechaza', () => Promise.reject(new Error('NotAllowedError'))],
  ])('%s, selecciona el valor y lo copia con el comando del documento', async (_caso, writeText) => {
    const user = userEvent.setup()
    conPortapapeles(writeText)
    const execCommand = conExecCommand(true)
    renderSecreto()
    await user.click(copiar())
    expect(execCommand).toHaveBeenCalledWith('copy')
    expect(seleccion()).toBe(SECRETO)
    expect(estado()).toHaveTextContent('Copiada al portapapeles.')
  })

  it.each([
    ['si el comando falla', () => conExecCommand(false)],
    ['si el navegador no tiene el comando', () => undefined],
  ])('%s, deja el valor seleccionado y pide copiarla a mano', async (_caso, preparar) => {
    const user = userEvent.setup()
    conPortapapeles(undefined)
    preparar()
    renderSecreto()
    await user.click(copiar())
    expect(seleccion()).toBe(SECRETO)
    expect(estado()).toHaveTextContent('Cópiala a mano: ya está seleccionada (Ctrl+C).')
    expect(copiar()).toHaveAccessibleName('Copiar')
  })

  it('al ocultarse la página, el valor ya no está en ese mismo instante y avisa una vez', async () => {
    const { onExpire } = renderSecreto()
    const texto = hidePageAndReadText()
    expect(texto).not.toContain(SECRETO)
    expect(texto).toContain(YA_NO)
    expect(onExpire).toHaveBeenCalledTimes(1)
    await act(async () => {})
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('cuando el que la usa la pasa a nulo tras el aviso, no vuelve a avisar', async () => {
    const { onExpire, rerender } = renderSecreto()
    hidePageAndReadText()
    await act(async () => {})
    rerender(<OneTimeSecret label="Contraseña temporal" value={null} onExpire={onExpire} />)
    hidePageAndReadText()
    await act(async () => {})
    expect(onExpire).toHaveBeenCalledTimes(1)
  })

  it('sin valor dice que ya no se muestra y no avisa, tampoco al ocultarse la página', async () => {
    const { onExpire } = renderSecreto(null)
    expect(screen.getByText(YA_NO)).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    hidePageAndReadText()
    await act(async () => {})
    expect(onExpire).not.toHaveBeenCalled()
  })

  it('al ocultarse la página avisa al último onExpire que recibió', async () => {
    const { onExpire: primero, rerender } = renderSecreto()
    const ultimo = vi.fn()
    rerender(<OneTimeSecret label="Contraseña temporal" value={SECRETO} onExpire={ultimo} />)
    hidePageAndReadText()
    await act(async () => {})
    expect(ultimo).toHaveBeenCalledTimes(1)
    expect(primero).not.toHaveBeenCalled()
  })

  it('desmontada, ocultar la página no avisa', async () => {
    const { onExpire, unmount } = renderSecreto()
    unmount()
    hidePageAndReadText()
    await act(async () => {})
    expect(onExpire).not.toHaveBeenCalled()
  })

  it('sin violaciones con el valor, copiada y ya borrada', async () => {
    const user = userEvent.setup()
    conPortapapeles(() => Promise.resolve())
    const { container, rerender } = renderSecreto()
    expect(await axe(container)).toHaveNoViolations()
    await user.click(copiar())
    expect(await axe(container)).toHaveNoViolations()
    rerender(<OneTimeSecret label="Contraseña temporal" value={null} onExpire={() => {}} />)
    expect(await axe(container)).toHaveNoViolations()
  })
})
