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

  it('el botón de crear trae la clase que lo apaga cuando queda deshabilitado', () => {
    // La clase vivía dentro de la constante y se aplicaba entera; ahora es una prop suelta
    // del componente compartido, que una edición futura puede soltar sola. Sin esto, el
    // botón quedaría deshabilitado para el DOM y encendido para el ojo.
    renderModal()
    expect(screen.getByRole('button', { name: /crear tipo de carga/i }).className).toContain(
      'disabled:bg-accent-disabled',
    )
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

  it('manda null en las dimensiones que nadie tocó', async () => {
    const user = userEvent.setup()
    const sink: { body?: CargoTypeRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.type(screen.getByLabelText('Nombre'), 'GRANEL')
    await user.type(screen.getByLabelText(/peso estándar/i), '1000')
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    await vi.waitFor(() => expect(sink.body).toBeDefined())
    // Este es el caso que ensuciaba el catálogo: la conversión del registro recibía
    // el valor por omisión del campo intacto y lo volvía cero, así que quedaba
    // guardado como si alguien hubiera escrito 0.
    expect(sink.body?.standardLength).toBeNull()
    expect(sink.body?.standardWidth).toBeNull()
    expect(sink.body?.standardHeight).toBeNull()
  })

  it('manda null en una dimensión que se escribió y se volvió a vaciar', async () => {
    const user = userEvent.setup()
    const sink: { body?: CargoTypeRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.type(screen.getByLabelText('Nombre'), 'GRANEL')
    await user.type(screen.getByLabelText(/peso estándar/i), '1000')
    const largo = screen.getByLabelText(/largo estándar/i)
    await user.type(largo, '5')
    await user.clear(largo)
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    await vi.waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body?.standardLength).toBeNull()
  })

  it('manda null en una dimensión borrada tecla por tecla', async () => {
    const user = userEvent.setup()
    const sink: { body?: CargoTypeRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.type(screen.getByLabelText('Nombre'), 'GRANEL')
    await user.type(screen.getByLabelText(/peso estándar/i), '1000')
    // Borrar de a una tecla no dispara los mismos eventos que vaciar de golpe, y el
    // resultado tiene que ser el mismo.
    await user.type(screen.getByLabelText(/largo estándar/i), '12{Backspace}{Backspace}')
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    await vi.waitFor(() => expect(sink.body).toBeDefined())
    expect(sink.body?.standardLength).toBeNull()
  })

  it('un campo cargado y otro intacto en el MISMO envío: uno con valor, el otro null', async () => {
    const user = userEvent.setup()
    const sink: { body?: CargoTypeRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.type(screen.getByLabelText('Nombre'), 'GRANEL')
    await user.type(screen.getByLabelText(/peso estándar/i), '1000')
    await user.type(screen.getByLabelText(/largo estándar/i), '12.5')
    // Ancho y alto no se tocan.
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    await vi.waitFor(() => expect(sink.body).toBeDefined())
    // Es la firma exacta del defecto: dos campos del mismo envío daban resultados
    // distintos según por dónde había pasado el usuario.
    expect(sink.body?.standardLength).toBe(12.5)
    expect(sink.body?.standardWidth).toBeNull()
    expect(sink.body?.standardHeight).toBeNull()
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

  it('exige el nombre y el peso antes de llamar al servidor', async () => {
    const user = userEvent.setup()
    const sink: { body?: CargoTypeRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    // Antes el peso arrancaba en 0 y este envío creaba un tipo de carga pesando
    // cero sin que nadie escribiera nada.
    const nombre = await screen.findByText('El nombre es obligatorio.')
    const peso = screen.getByText('Ingresa el peso estándar (kg).')
    expect(nombre).toHaveAttribute('role', 'alert')
    expect(peso).toHaveAttribute('role', 'alert')
    expect(sink.body).toBeUndefined()
  })

  it('el peso arranca vacío, no en cero', () => {
    renderModal()
    expect((screen.getByLabelText(/peso estándar/i) as HTMLInputElement).value).toBe('')
  })

  it('los cuatro campos numéricos ofrecen 0.01 como mínimo, no 0', () => {
    renderModal()
    // El spinner del navegador no debe ofrecer el valor que el formulario rechaza.
    for (const etiqueta of [/peso estándar/i, /largo estándar/i, /ancho estándar/i, /alto estándar/i]) {
      expect(screen.getByLabelText(etiqueta)).toHaveAttribute('min', '0.01')
    }
  })

  it('no deja enviar un cero escrito a mano en una dimensión', async () => {
    const user = userEvent.setup()
    const sink: { body?: CargoTypeRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.type(screen.getByLabelText('Nombre'), 'GRANEL')
    await user.type(screen.getByLabelText(/peso estándar/i), '1000')
    await user.type(screen.getByLabelText(/largo estándar/i), '0')
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    // El cero se rechaza igual que en el peso: una medida en cero no existe, y el
    // campo vacío ya dice "no la sé". El servidor aplica la misma regla.
    expect(await screen.findByText('La medida debe ser mayor a 0.')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('rechaza el peso en cero escrito a mano', async () => {
    const user = userEvent.setup()
    const sink: { body?: CargoTypeRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.type(screen.getByLabelText('Nombre'), 'GRANEL')
    await user.type(screen.getByLabelText(/peso estándar/i), '0')
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    // Una carga que pesa cero no existe: ese cero decía "no lo cargué".
    expect(await screen.findByText('El peso estándar debe ser mayor a 0.')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })

  it('rechaza el peso que se escribió y se volvió a vaciar', async () => {
    const user = userEvent.setup()
    const sink: { body?: CargoTypeRequest } = {}
    server.use(captureCreate(sink))
    renderModal()

    await user.type(screen.getByLabelText('Nombre'), 'GRANEL')
    const peso = screen.getByLabelText(/peso estándar/i)
    await user.type(peso, '77')
    await user.clear(peso)
    await user.click(screen.getByRole('button', { name: /crear tipo de carga/i }))

    expect(await screen.findByText('Ingresa el peso estándar (kg).')).toBeInTheDocument()
    expect(sink.body).toBeUndefined()
  })
})
