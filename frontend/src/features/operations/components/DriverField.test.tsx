import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DriverField } from './DriverField'
import { server } from '../../../test/mocks/server'
import { driversError, driversList, fakeDriver } from '../../../test/mocks/handlers/operations'

/**
 * Tres conductores con nombre, licencia y CATEGORÍA distintos entre sí: cada término
 * de búsqueda deja uno solo, así que buscar por un campo que el filtro no mire no
 * encuentra nada y el caso lo ve.
 */
const DRIVERS = [
  fakeDriver({ id: 4, fullName: 'Juan Pérez Huamán', licenseNumber: 'Q12345678', licenseCategory: 'A-IIIC' }),
  fakeDriver({ id: 8, fullName: 'Ana Ríos Chávez', licenseNumber: 'W22222222', licenseCategory: 'B-IIB' }),
  fakeDriver({ id: 15, fullName: 'Luis Quispe Mamani', licenseNumber: 'Z33333333', licenseCategory: 'A-IIA' }),
]

function renderField(props: Partial<Parameters<typeof DriverField>[0]> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onSelectedChange = vi.fn()
  render(
    <DriverField
      id="driver"
      label="Conductor"
      selected={null}
      onSelectedChange={onSelectedChange}
      placeholder="Busca un conductor…"
      loadErrorText="No se pudo cargar el padrón."
      {...props}
    />,
    {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      ),
    },
  )
  return { onSelectedChange }
}

describe('DriverField', () => {
  it('ofrece el padrón entero y muestra licencia y disponibilidad', async () => {
    server.use(driversList(DRIVERS))
    const user = userEvent.setup()
    renderField()

    await user.click(screen.getByLabelText('Conductor'))
    const listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByText('Juan Pérez Huamán')).toBeInTheDocument()
    expect(within(listbox).getByText('Q12345678 · Disponible')).toBeInTheDocument()
  })

  it('busca por nombre', async () => {
    server.use(driversList(DRIVERS))
    const user = userEvent.setup()
    renderField()

    await user.type(screen.getByLabelText('Conductor'), 'quispe')

    const listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByText('Luis Quispe Mamani')).toBeInTheDocument()
    expect(within(listbox).queryByText('Juan Pérez Huamán')).not.toBeInTheDocument()
    expect(within(listbox).queryByText('Ana Ríos Chávez')).not.toBeInTheDocument()
  })

  it('busca por número de licencia', async () => {
    server.use(driversList(DRIVERS))
    const user = userEvent.setup()
    renderField()

    await user.type(screen.getByLabelText('Conductor'), 'W2222')

    const listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByText('Ana Ríos Chávez')).toBeInTheDocument()
    expect(within(listbox).queryByText('Luis Quispe Mamani')).not.toBeInTheDocument()
  })

  it('busca por categoría de licencia', async () => {
    server.use(driversList(DRIVERS))
    const user = userEvent.setup()
    renderField()

    // Es la mitad de lo que el javadoc promete y el único campo que no aparece en la
    // pantalla, así que sin este caso se puede borrar del filtro sin que nada avise.
    await user.type(screen.getByLabelText('Conductor'), 'B-IIB')

    const listbox = await screen.findByRole('listbox')
    expect(await within(listbox).findByText('Ana Ríos Chávez')).toBeInTheDocument()
    expect(within(listbox).queryByText('Juan Pérez Huamán')).not.toBeInTheDocument()
    expect(within(listbox).queryByText('Luis Quispe Mamani')).not.toBeInTheDocument()
  })

  it('devuelve el conductor entero, no solo su id', async () => {
    server.use(driversList(DRIVERS))
    const user = userEvent.setup()
    const { onSelectedChange } = renderField()

    await user.click(screen.getByLabelText('Conductor'))
    const listbox = await screen.findByRole('listbox')
    await user.click(await within(listbox).findByText('Ana Ríos Chávez'))

    // El objeto completo: el consumidor guarda el id pero muestra el nombre, y con
    // solo el id los tres conductores serían indistinguibles al volver a pintarlos.
    expect(onSelectedChange).toHaveBeenCalledWith(DRIVERS[1])
  })

  it('conserva el conductor ya elegido aunque el padrón no lo traiga', async () => {
    // El caso real: un viaje asignado a un conductor que después se dio de baja. El
    // padrón pide solo los activos, así que no viene, y aun así tiene que seguir
    // viéndose elegido.
    server.use(driversList([DRIVERS[1]]))
    renderField({ selected: { id: 4, fullName: 'Juan Pérez Huamán' } })

    await waitFor(() => expect(screen.getByText('Juan Pérez Huamán')).toBeInTheDocument())
  })

  it('avisa con el mensaje del consumidor cuando el padrón no carga', async () => {
    server.use(driversError(500))
    const user = userEvent.setup()
    renderField()

    await user.click(screen.getByLabelText('Conductor'))

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('No se pudo cargar el padrón.')
  })

  it('marca el campo como inválido cuando el consumidor le pasa un error', async () => {
    server.use(driversList(DRIVERS))
    const user = userEvent.setup()
    renderField({ error: 'Selecciona el conductor' })
    // Se espera a que el padrón llegue antes de mirar, para no dejar el pedido en
    // vuelo resolviendo después del desmontaje.
    await user.click(screen.getByLabelText('Conductor'))
    await screen.findByRole('listbox')

    const field = screen.getByLabelText('Conductor')
    expect(field).toHaveAttribute('aria-invalid', 'true')
    const describedBy = field.getAttribute('aria-describedby')
    expect(describedBy).toBeTruthy()
    expect(document.getElementById(describedBy as string)).toHaveTextContent(
      'Selecciona el conductor',
    )
  })
})
