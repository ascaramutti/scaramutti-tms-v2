import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { CargoTypeCreateModal } from './CargoTypeCreateModal'
import type { CargoTypeRequest, CargoTypeResponse } from '../../../api'
import { server } from '../../../test/mocks/server'
import { createCargoTypeConflict, fakeCargoType } from '../../../test/mocks/handlers/cargotypes'

const API = 'http://localhost:8080/api/v1'

/** Captura el cuerpo del alta: lo que importa es qué se manda, no que no explote. */
function captureCreate(sink: { body?: CargoTypeRequest }, response?: CargoTypeResponse) {
  return http.post(`${API}/cargo-types`, async ({ request }) => {
    sink.body = (await request.json()) as CargoTypeRequest
    return HttpResponse.json(response ?? fakeCargoType({ id: 99 }), { status: 201 })
  })
}

function renderModal({ initialName = '' } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onCreated = vi.fn()
  const onClose = vi.fn()
  render(
    <QueryClientProvider client={queryClient}>
      <CargoTypeCreateModal initialName={initialName} onClose={onClose} onCreated={onCreated} />
    </QueryClientProvider>,
  )
  return { onCreated, onClose }
}

describe('CargoTypeCreateModal', () => {
  it('precarga el nombre con lo que se venía buscando', () => {
    renderModal({ initialName: 'contenedor' })
    expect((screen.getByLabelText('Nombre') as HTMLInputElement).value).toBe('contenedor')
  })

  it('manda al servidor todo lo que se cargó, no solo el nombre', async () => {
    const user = userEvent.setup()
    const sink: { body?: CargoTypeRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.type(screen.getByLabelText('Nombre'), 'CONTENEDOR 40')
    await user.type(screen.getByLabelText(/descripción/i), 'Cerrado, alto cubo')
    await user.type(screen.getByLabelText(/peso estándar/i), '3800')
    await user.type(screen.getByLabelText(/largo estándar/i), '12.19')
    await user.type(screen.getByLabelText(/ancho estándar/i), '2.44')
    await user.type(screen.getByLabelText(/alto estándar/i), '2.9')
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    await vi.waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body).toEqual({
      name: 'CONTENEDOR 40',
      description: 'Cerrado, alto cubo',
      standardWeight: 3800,
      standardLength: 12.19,
      standardWidth: 2.44,
      standardHeight: 2.9,
    })
  })

  it('hoy manda 0 en las dimensiones vacías, que es la deuda que arrastra el catálogo', async () => {
    const user = userEvent.setup()
    const sink: { body?: CargoTypeRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.type(screen.getByLabelText('Nombre'), 'GRANEL')
    await user.type(screen.getByLabelText(/peso estándar/i), '1000')
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    await vi.waitFor(() => expect(sink.body).toBeDefined())
    // Leyendo el código se esperaría null: el mapeo dice `?? null` y el valor por
    // omisión es null. Pero el campo se registra como número y react-hook-form
    // relee el input vacío del DOM como cero, así que lo que sale es 0. Queda
    // afirmado para que el arreglo (que es de otro módulo y toca cotizaciones en
    // producción) tenga que venir a cambiar este test a propósito.
    expect(sink.body?.standardLength).toBe(0)
    expect(sink.body?.standardWidth).toBe(0)
    expect(sink.body?.standardHeight).toBe(0)
  })

  it('manda la descripción en null cuando quedó vacía', async () => {
    const user = userEvent.setup()
    const sink: { body?: CargoTypeRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.type(screen.getByLabelText('Nombre'), 'GRANEL')
    await user.type(screen.getByLabelText(/peso estándar/i), '1000')
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    await vi.waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body?.description).toBeNull()
  })

  it('devuelve el tipo de carga creado a quien abrió el modal', async () => {
    const user = userEvent.setup()
    const creado = fakeCargoType({ id: 99, name: 'GRANEL', standardWeight: 1000 })
    server.use(captureCreate({}, creado))
    const { onCreated } = renderModal()

    await user.type(screen.getByLabelText('Nombre'), 'GRANEL')
    await user.type(screen.getByLabelText(/peso estándar/i), '1000')
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    await vi.waitFor(() => expect(onCreated).toHaveBeenCalledWith(creado))
  })

  it('el nombre duplicado se explica en el campo nombre, no en un aviso suelto', async () => {
    const user = userEvent.setup()
    server.use(createCargoTypeConflict('Ya existe un tipo de carga con ese nombre.'))
    const { onCreated } = renderModal()

    await user.type(screen.getByLabelText('Nombre'), 'CARGA GENERAL')
    await user.type(screen.getByLabelText(/peso estándar/i), '1000')
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    expect(await screen.findByText('Ya existe un tipo de carga con ese nombre.')).toBeInTheDocument()
    expect(onCreated).not.toHaveBeenCalled()
  })

  it('exige el nombre antes de llamar al servidor', async () => {
    const user = userEvent.setup()
    const sink: { body?: CargoTypeRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })
})
