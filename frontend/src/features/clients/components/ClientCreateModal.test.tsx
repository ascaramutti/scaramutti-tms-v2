import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { ClientCreateModal } from './ClientCreateModal'
import type { ClientRequest, ClientResponse } from '../../../api'
import { server } from '../../../test/mocks/server'
import { createClientConflict, fakeClient } from '../../../test/mocks/handlers/clients'

const API = 'http://localhost:8080/api/v1'

/** Captura el cuerpo del alta: lo que importa es qué se manda. */
function captureCreate(sink: { body?: ClientRequest }, response?: ClientResponse) {
  return http.post(`${API}/clients`, async ({ request }) => {
    sink.body = (await request.json()) as ClientRequest
    return HttpResponse.json(response ?? fakeClient({ id: 99 }), { status: 201 })
  })
}

function renderModal({ initialName = '' } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onCreated = vi.fn()
  const onClose = vi.fn()
  render(
    <QueryClientProvider client={queryClient}>
      <ClientCreateModal initialName={initialName} onClose={onClose} onCreated={onCreated} />
    </QueryClientProvider>,
  )
  return { onCreated, onClose }
}

/** El campo RUC del formulario, distinguido del RUC de solo lectura de la pantalla. */
function rucField() {
  return within(screen.getByRole('dialog')).getByLabelText('RUC')
}

describe('ClientCreateModal', () => {
  it('precarga la razón social con lo que se venía buscando', () => {
    renderModal({ initialName: 'transportes del norte' })
    expect((screen.getByLabelText('Razón social') as HTMLInputElement).value).toBe(
      'transportes del norte',
    )
  })

  it('manda al servidor los cuatro datos cargados', async () => {
    const user = userEvent.setup()
    const sink: { body?: ClientRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.type(screen.getByLabelText('Razón social'), 'NUEVA SAC')
    await user.type(rucField(), '20111111111')
    await user.type(screen.getByLabelText(/persona de contacto/i), 'Ana Torres')
    await user.type(screen.getByLabelText(/teléfono/i), '987654321')
    await user.click(screen.getByRole('button', { name: /crear cliente/i }))

    await vi.waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body).toEqual({
      name: 'NUEVA SAC',
      ruc: '20111111111',
      contactName: 'Ana Torres',
      phone: '987654321',
    })
  })

  it('manda en null el contacto y el teléfono que quedaron vacíos', async () => {
    const user = userEvent.setup()
    const sink: { body?: ClientRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.type(screen.getByLabelText('Razón social'), 'NUEVA SAC')
    await user.type(rucField(), '20111111111')
    await user.click(screen.getByRole('button', { name: /crear cliente/i }))

    await vi.waitFor(() => expect(sink.body).toBeDefined())
    // Null y no cadena vacía: el cliente no tiene contacto, no tiene un contacto sin nombre.
    expect(sink.body?.contactName).toBeNull()
    expect(sink.body?.phone).toBeNull()
  })

  it('devuelve el cliente creado a quien abrió el modal', async () => {
    const user = userEvent.setup()
    const creado = fakeClient({ id: 99, name: 'NUEVA SAC', ruc: '20111111111' })
    server.use(captureCreate({}, creado))
    const { onCreated } = renderModal()

    await user.type(screen.getByLabelText('Razón social'), 'NUEVA SAC')
    await user.type(rucField(), '20111111111')
    await user.click(screen.getByRole('button', { name: /crear cliente/i }))

    await vi.waitFor(() => expect(onCreated).toHaveBeenCalledWith(creado))
  })

  it('el RUC repetido se explica en el campo RUC, que es el dato a corregir', async () => {
    const user = userEvent.setup()
    server.use(createClientConflict('El RUC ya existe en otro cliente.'))
    const { onCreated } = renderModal()

    await user.type(screen.getByLabelText('Razón social'), 'DUPLICADA SAC')
    await user.type(rucField(), '20123456789')
    await user.click(screen.getByRole('button', { name: /crear cliente/i }))

    expect(await screen.findByText('El RUC ya existe en otro cliente.')).toBeInTheDocument()
    expect(onCreated).not.toHaveBeenCalled()
  })

  it('exige la razón social y el RUC antes de llamar al servidor', async () => {
    const user = userEvent.setup()
    const sink: { body?: ClientRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.click(screen.getByRole('button', { name: /crear cliente/i }))

    // Los dos mensajes, no solo que sean dos: contar deja pasar que ambos sean el
    // mensaje equivocado.
    expect(await screen.findByText('La razón social es obligatoria.')).toBeInTheDocument()
    expect(screen.getByText('El RUC debe tener 11 dígitos.')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('el botón de crear trae la clase que lo apaga cuando queda deshabilitado', () => {
    // La clase que lo apaga (`disabled:bg-blue-300`) vivía dentro de la constante y se
    // aplicaba entera; ahora es una prop aparte del componente compartido. Sin esta línea,
    // soltarla dejaba el botón apagado para el DOM y encendido para el ojo.
    renderModal()
    expect(screen.getByRole('button', { name: /crear cliente/i }).className).toContain(
      'disabled:bg-blue-300',
    )
  })

  it('cerrar sin crear no registra nada', async () => {
    const user = userEvent.setup()
    const { onClose, onCreated } = renderModal()

    await user.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(onClose).toHaveBeenCalled()
    expect(onCreated).not.toHaveBeenCalled()
  })
})
