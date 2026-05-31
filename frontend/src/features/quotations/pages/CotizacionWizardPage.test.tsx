import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CotizacionWizardPage } from './CotizacionWizardPage'
import { server } from '../../../test/mocks/server'
import { catalogsSlow, configOk, currenciesError } from '../../../test/mocks/handlers/catalogs'
import {
  clientsCapture,
  clientsSearch,
  createClientConflict,
  createClientOk,
  fakeClient,
} from '../../../test/mocks/handlers/clients'

function renderWizard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/cotizaciones/nueva']}>
        <Routes>
          <Route path="/cotizaciones" element={<div>LISTADO COTIZACIONES</div>} />
          <Route path="/cotizaciones/nueva" element={<CotizacionWizardPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Espera a que el form del step 1 esté montado (tras cargar catálogos). */
function waitForForm() {
  return screen.findByText('Tipo de cotización')
}

/** Busca y selecciona el cliente ACME (requiere `clientsSearch([fakeClient()])`). */
async function selectAcme(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Cliente'), 'acme')
  await user.click(await screen.findByText('ACME S.A.C.'))
}

describe('CotizacionWizardPage', () => {
  // ----- Render -----
  it('monta el wizard con el stepper de 4 pasos y el Step 1', async () => {
    renderWizard()
    await waitForForm()
    expect(screen.getByText('Información General')).toBeInTheDocument()
    expect(screen.getByText('Ítems')).toBeInTheDocument()
    expect(screen.getByText('Resumen')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /transporte/i })).toBeInTheDocument()
  })

  it('precarga los defaults comerciales (moneda PEN, validez del config)', async () => {
    renderWizard()
    await waitForForm()
    expect((screen.getByLabelText('Moneda') as HTMLSelectElement).value).toBe('2') // PEN
    expect((screen.getByLabelText(/validez/i) as HTMLInputElement).value).toBe('15')
  })

  it('muestra el spinner mientras cargan los catálogos', async () => {
    server.use(...catalogsSlow(40))
    renderWizard()
    expect(screen.getByRole('status')).toBeInTheDocument()
    await waitForForm()
  })

  // ----- Tipo de cotización -----
  it('TRANSPORTE (default) muestra los campos de ruta', async () => {
    renderWizard()
    await waitForForm()
    expect(screen.getByLabelText('Origen')).toBeInTheDocument()
    expect(screen.getByLabelText('Destino')).toBeInTheDocument()
  })

  it('ALQUILER oculta los campos de ruta', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /alquiler/i }))
    expect(screen.queryByLabelText('Origen')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Destino')).not.toBeInTheDocument()
  })

  // ----- Combobox cliente -----
  it('no busca clientes con menos de 3 caracteres y muestra el hint', async () => {
    const user = userEvent.setup()
    const requests: string[] = []
    const onRequest = ({ request }: { request: Request }) => requests.push(request.url)
    server.events.on('request:start', onRequest)
    renderWizard()
    await waitForForm()
    await user.type(screen.getByLabelText('Cliente'), 'ac')
    expect(await screen.findByText(/al menos 3 caracteres/i)).toBeInTheDocument()
    await new Promise((resolve) => setTimeout(resolve, 450))
    expect(requests.some((url) => url.includes('/clients?') && url.includes('q='))).toBe(false)
    server.events.removeListener('request:start', onRequest)
  })

  it('busca clientes con 3 o más caracteres (param q)', async () => {
    const user = userEvent.setup()
    const sink: { params?: URLSearchParams } = {}
    server.use(clientsCapture(sink, [fakeClient()]))
    renderWizard()
    await waitForForm()
    await user.type(screen.getByLabelText('Cliente'), 'acme')
    await screen.findByText('ACME S.A.C.')
    expect(sink.params?.get('q')).toBe('acme')
  })

  it('al seleccionar un cliente precarga el contacto', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ contactName: 'Juan Pérez', phone: '987654321' })]))
    renderWizard()
    await waitForForm()
    await selectAcme(user)
    expect((screen.getByLabelText(/persona de contacto/i) as HTMLInputElement).value).toBe('Juan Pérez')
    expect((screen.getByLabelText(/teléfono de contacto/i) as HTMLInputElement).value).toBe('987654321')
  })

  it('quitar el cliente limpia el contacto precargado', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ contactName: 'Juan Pérez', phone: '987654321' })]))
    renderWizard()
    await waitForForm()
    await selectAcme(user)
    expect((screen.getByLabelText(/persona de contacto/i) as HTMLInputElement).value).toBe('Juan Pérez')
    await user.click(screen.getByRole('button', { name: /quitar selección/i }))
    expect((screen.getByLabelText(/persona de contacto/i) as HTMLInputElement).value).toBe('')
    expect((screen.getByLabelText(/teléfono de contacto/i) as HTMLInputElement).value).toBe('')
  })

  it('al seleccionar un cliente muestra su RUC en un campo de solo lectura', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ ruc: '20123456789' })]))
    renderWizard()
    await waitForForm()
    await selectAcme(user)
    const ruc = screen.getByLabelText('RUC del cliente seleccionado') as HTMLInputElement
    expect(ruc.value).toBe('20123456789')
    expect(ruc).toHaveAttribute('readonly')
  })

  // ----- Crear cliente al vuelo -----
  it('el botón "Nuevo cliente" abre el modal', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.type(screen.getByLabelText('Cliente'), 'nuevo')
    await user.click(await screen.findByText('Nuevo cliente'))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('crear un cliente válido lo selecciona y cierra el modal', async () => {
    const user = userEvent.setup()
    server.use(createClientOk(fakeClient({ id: 99, name: 'NUEVA SAC', ruc: '20111111111', contactName: 'Ana' })))
    renderWizard()
    await waitForForm()
    await user.type(screen.getByLabelText('Cliente'), 'nueva')
    await user.click(await screen.findByText('Nuevo cliente'))
    await user.type(screen.getByLabelText('Razón social'), 'NUEVA SAC')
    await user.type(within(screen.getByRole('dialog')).getByLabelText('RUC'), '20111111111')
    await user.click(screen.getByRole('button', { name: /crear cliente/i }))
    expect(await screen.findByText('NUEVA SAC')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('RUC duplicado (409) muestra el Problem.detail en el modal', async () => {
    const user = userEvent.setup()
    server.use(createClientConflict('El RUC ya existe en otro cliente.'))
    renderWizard()
    await waitForForm()
    await user.type(screen.getByLabelText('Cliente'), 'dup')
    await user.click(await screen.findByText('Nuevo cliente'))
    await user.type(screen.getByLabelText('Razón social'), 'DUP SAC')
    await user.type(within(screen.getByRole('dialog')).getByLabelText('RUC'), '20123456789')
    await user.click(screen.getByRole('button', { name: /crear cliente/i }))
    expect(await screen.findByText('El RUC ya existe en otro cliente.')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  // ----- Validación Step 1 -----
  it('"Siguiente" sin cliente muestra error y no avanza', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    expect(await screen.findByText(/seleccioná un cliente/i)).toBeInTheDocument()
    expect(screen.getByText('Tipo de cotización')).toBeInTheDocument() // sigue en step 1
  })

  it('teléfono de contacto con formato inválido muestra error', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.type(screen.getByLabelText(/teléfono de contacto/i), '123')
    await user.tab()
    expect(await screen.findByText(/9 dígitos/i)).toBeInTheDocument()
  })

  it('origen es obligatorio en TRANSPORTE', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    expect(await screen.findByText(/origen es obligatorio/i)).toBeInTheDocument()
  })

  // ----- Navegación -----
  it('"Siguiente" con el Step 1 válido (alquiler) avanza al Step 2', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient({ contactName: 'Juan Pérez', phone: '987654321' })]))
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /alquiler/i }))
    await selectAcme(user)
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    expect(await screen.findByText(/ítems del servicio/i)).toBeInTheDocument()
  })

  it('"Atrás" vuelve al Step 1 conservando los datos', async () => {
    const user = userEvent.setup()
    server.use(clientsSearch([fakeClient()]))
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /alquiler/i }))
    await selectAcme(user)
    await user.click(screen.getByRole('button', { name: /siguiente/i }))
    await screen.findByText(/ítems del servicio/i)
    await user.click(screen.getByRole('button', { name: /atrás/i }))
    expect(await screen.findByText('Tipo de cotización')).toBeInTheDocument()
    expect(screen.getByText('ACME S.A.C.')).toBeInTheDocument() // cliente conservado
  })

  it('"Cancelar" navega al listado', async () => {
    const user = userEvent.setup()
    renderWizard()
    await waitForForm()
    await user.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(await screen.findByText('LISTADO COTIZACIONES')).toBeInTheDocument()
  })

  // ----- Catálogos -----
  it('toma la validez por defecto del config', async () => {
    server.use(configOk({ defaultValidityDays: 20 }))
    renderWizard()
    await waitForForm()
    expect((screen.getByLabelText(/validez/i) as HTMLInputElement).value).toBe('20')
  })

  it('si fallan los catálogos muestra error y permite reintentar', async () => {
    server.use(currenciesError(500))
    renderWizard()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /reintentar/i })).toBeInTheDocument()
  })

  // ----- A11y -----
  it('los campos del Step 1 tienen labels accesibles', async () => {
    renderWizard()
    await waitForForm()
    expect(screen.getByLabelText('Cliente')).toBeInTheDocument()
    expect(screen.getByLabelText(/persona de contacto/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Moneda')).toBeInTheDocument()
    expect(screen.getByLabelText(/validez/i)).toBeInTheDocument()
  })
})
