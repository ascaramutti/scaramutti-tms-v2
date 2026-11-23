import { useState } from 'react'
import type { ReactNode } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Modal } from './Modal'

function Llamador({ dismissible, children }: { dismissible?: boolean; children?: ReactNode }) {
  const [abierto, setAbierto] = useState(false)
  return (
    <>
      <button type="button" onClick={() => setAbierto(true)}>
        Abrir
      </button>
      {abierto && (
        <Modal isOpen title="Un diálogo" onClose={() => setAbierto(false)} dismissible={dismissible}>
          {children ?? (
            <>
              <button type="button">Primero</button>
              <button type="button" onClick={() => setAbierto(false)}>
                Último
              </button>
            </>
          )}
        </Modal>
      )}
    </>
  )
}

const dialogo = () => screen.getByRole('dialog')
const fondo = () => dialogo().previousElementSibling as HTMLElement
const boton = (name: string) => within(dialogo()).getByRole('button', { name })

async function abrir(props: { dismissible?: boolean; children?: ReactNode } = {}) {
  const user = userEvent.setup()
  render(<Llamador {...props} />)
  await user.click(screen.getByRole('button', { name: 'Abrir' }))
  return user
}

const VIAS: [string, (user: ReturnType<typeof userEvent.setup>) => Promise<void>][] = [
  ['Escape', (user) => user.keyboard('{Escape}')],
  ['el fondo', (user) => user.click(fondo())],
  ['"Cerrar"', (user) => user.click(boton('Cerrar'))],
]

describe('Modal', () => {
  describe('sin la prop', () => {
    it.each(VIAS)('%s lo cierra una vez', async (_via, cerrar) => {
      const user = userEvent.setup()
      const onClose = vi.fn()
      render(
        <Modal isOpen title="Un diálogo" onClose={onClose}>
          <p>Contenido</p>
        </Modal>,
      )
      await cerrar(user)
      expect(onClose).toHaveBeenCalledTimes(1)
    })

    it('sin campos, el foco al abrir va a "Cerrar", y al cerrar vuelve al que lo abrió', async () => {
      const user = await abrir()
      expect(boton('Cerrar')).toHaveFocus()
      await user.keyboard('{Escape}')
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Abrir' })).toHaveFocus()
    })

    it('con un campo, el foco al abrir va al campo', async () => {
      await abrir({ children: <input aria-label="Motivo" /> })
      expect(within(dialogo()).getByRole('textbox', { name: 'Motivo' })).toHaveFocus()
    })

    it('el Tab da la vuelta dentro del diálogo, en los dos sentidos', async () => {
      const user = await abrir()
      boton('Último').focus()
      await user.tab()
      expect(boton('Cerrar')).toHaveFocus()
      await user.tab({ shift: true })
      expect(boton('Último')).toHaveFocus()
    })

    it('volver a dibujarlo con otro onClose no le mueve el foco', async () => {
      const user = userEvent.setup()
      const { rerender } = render(
        <Modal isOpen title="Un diálogo" onClose={() => {}}>
          <button type="button">Primero</button>
        </Modal>,
      )
      boton('Primero').focus()
      const onClose = vi.fn()
      rerender(
        <Modal isOpen title="Un diálogo" onClose={onClose}>
          <button type="button">Primero</button>
        </Modal>,
      )
      expect(boton('Primero')).toHaveFocus()
      await user.keyboard('{Escape}')
      expect(onClose).toHaveBeenCalledTimes(1)
    })

    it('es mediano por omisión', () => {
      render(
        <Modal isOpen title="Un diálogo" onClose={() => {}}>
          <p>Contenido</p>
        </Modal>,
      )
      expect(dialogo().className).toContain('max-w-lg')
    })
  })

  describe('con dismissible en false', () => {
    it('apagarla con el diálogo abierto no le mueve el foco, y Escape deja de cerrarlo', async () => {
      const user = userEvent.setup()
      const onClose = vi.fn()
      const contenido = (
        <>
          <button type="button">Primero</button>
          <button type="button">Último</button>
        </>
      )
      const { rerender } = render(
        <Modal isOpen title="Un diálogo" onClose={onClose}>
          {contenido}
        </Modal>,
      )
      boton('Último').focus()
      rerender(
        <Modal isOpen title="Un diálogo" onClose={onClose} dismissible={false}>
          {contenido}
        </Modal>,
      )
      expect(boton('Último')).toHaveFocus()
      await user.keyboard('{Escape}')
      expect(onClose).not.toHaveBeenCalled()
    })

    function renderFijo(onClose = vi.fn()) {
      render(
        <Modal isOpen title="Un diálogo" onClose={onClose} dismissible={false}>
          <button type="button">Primero</button>
          <button type="button">Último</button>
        </Modal>,
      )
      return onClose
    }

    it('no tiene "Cerrar"', () => {
      renderFijo()
      expect(within(dialogo()).queryByRole('button', { name: 'Cerrar' })).not.toBeInTheDocument()
    })

    it.each(VIAS.slice(0, 2))('%s no lo cierra', async (_via, cerrar) => {
      const user = userEvent.setup()
      const onClose = renderFijo()
      await cerrar(user)
      expect(onClose).not.toHaveBeenCalled()
      expect(dialogo()).toBeInTheDocument()
    })

    it('el foco al abrir va al primero del contenido, y el Tab sigue dando la vuelta', async () => {
      const user = await abrir({ dismissible: false })
      expect(boton('Primero')).toHaveFocus()
      boton('Último').focus()
      await user.tab()
      expect(boton('Primero')).toHaveFocus()
    })
  })
})
