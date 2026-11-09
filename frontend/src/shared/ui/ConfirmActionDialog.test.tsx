import { useState } from 'react'
import type { ComponentProps } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { axe } from 'vitest-axe'
import { ConfirmActionDialog } from './ConfirmActionDialog'

type Props = ComponentProps<typeof ConfirmActionDialog>

const BASE: Props = {
  title: 'Archivar la ficha',
  children: <p>¿Archivas la ficha?</p>,
  confirmLabel: 'Archivar',
  pendingLabel: 'Archivando…',
  confirmVariant: 'danger',
  isPending: false,
  error: null,
  onConfirm: () => {},
  onClose: () => {},
}

function renderDialogo(props: Partial<Props> = {}) {
  return render(<ConfirmActionDialog {...BASE} {...props} />)
}

function Llamador() {
  const [abierto, setAbierto] = useState(false)
  return (
    <>
      <button type="button" onClick={() => setAbierto(true)}>
        Abrir
      </button>
      {abierto && <ConfirmActionDialog {...BASE} onClose={() => setAbierto(false)} />}
    </>
  )
}

const dialogo = () => screen.getByRole('dialog')
const boton = (name: string | RegExp) => within(dialogo()).getByRole('button', { name })

const VIAS: [string, (user: ReturnType<typeof userEvent.setup>) => Promise<void>][] = [
  ['Escape', (user) => user.keyboard('{Escape}')],
  ['el fondo', (user) => user.click(dialogo().previousElementSibling as HTMLElement)],
  ['"Cerrar"', (user) => user.click(boton('Cerrar'))],
  ['"Cancelar"', (user) => user.click(boton('Cancelar'))],
]

describe('ConfirmActionDialog', () => {
  it.each(VIAS)('sin envío, %s lo cierra', async (_via, cerrar) => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderDialogo({ onClose })
    await cerrar(user)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it.each(VIAS)('mientras envía, %s no lo cierra', async (_via, cerrar) => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderDialogo({ onClose, isPending: true })
    await cerrar(user)
    expect(onClose).not.toHaveBeenCalled()
  })

  it('mientras envía, los dos botones quedan deshabilitados y apagados, y confirmar dice que envía', () => {
    renderDialogo({ isPending: true })
    for (const enviando of [boton(/Archivando…/), boton('Cancelar')]) {
      expect(enviando).toBeDisabled()
      expect(enviando.className).toContain('disabled:opacity-60')
    }
  })

  it('confirmar avisa una vez al que llama, sin cerrar', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    const onClose = vi.fn()
    renderDialogo({ onConfirm, onClose })
    await user.click(boton('Archivar'))
    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(onClose).not.toHaveBeenCalled()
  })

  it('el error se anuncia en el diálogo y, cuando ya no envía, el foco vuelve a confirmar', () => {
    const { rerender } = renderDialogo({ isPending: true })
    ;(document.activeElement as HTMLElement).blur()
    expect(document.body).toHaveFocus()
    rerender(<ConfirmActionDialog {...BASE} error="No se pudo archivar." />)
    expect(within(dialogo()).getByRole('alert')).toHaveTextContent('No se pudo archivar.')
    expect(boton('Archivar')).toHaveFocus()
  })

  it('con el mismo error a la vista, el foco vuelve a confirmar al terminar el reintento', () => {
    const { rerender } = renderDialogo({ error: 'No se pudo archivar.' })
    boton('Cerrar').focus()
    rerender(<ConfirmActionDialog {...BASE} error="No se pudo archivar." isPending />)
    boton('Cerrar').blur()
    expect(document.body).toHaveFocus()
    rerender(<ConfirmActionDialog {...BASE} error="No se pudo archivar." />)
    expect(boton('Archivar')).toHaveFocus()
  })

  it('un error que llega sin envío también lleva el foco a confirmar', () => {
    const { rerender } = renderDialogo()
    ;(document.activeElement as HTMLElement).blur()
    expect(document.body).toHaveFocus()
    rerender(<ConfirmActionDialog {...BASE} error="No se pudo archivar." />)
    expect(boton('Archivar')).toHaveFocus()
  })

  it('al abrir, el foco va a "Cerrar", y al cancelar vuelve al botón que lo abrió', async () => {
    const user = userEvent.setup()
    render(<Llamador />)
    await user.click(screen.getByRole('button', { name: 'Abrir' }))
    expect(boton('Cerrar')).toHaveFocus()
    await user.click(boton('Cancelar'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Abrir' })).toHaveFocus()
  })

  it('"Cancelar" va antes que confirmar', () => {
    renderDialogo()
    expect(boton('Cancelar').compareDocumentPosition(boton('Archivar')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('el título nombra al diálogo, que muestra lo que le pasa el que llama', () => {
    renderDialogo()
    expect(screen.getByRole('dialog', { name: 'Archivar la ficha' })).toHaveTextContent('¿Archivas la ficha?')
  })

  it('el diálogo es chico', () => {
    renderDialogo()
    expect(dialogo().className).toContain('max-w-sm')
  })

  it('sin violaciones abierto y con un error', async () => {
    const { rerender } = renderDialogo()
    expect(await axe(dialogo())).toHaveNoViolations()
    rerender(<ConfirmActionDialog {...BASE} error="No se pudo archivar." />)
    expect(await axe(dialogo())).toHaveNoViolations()
  })
})
