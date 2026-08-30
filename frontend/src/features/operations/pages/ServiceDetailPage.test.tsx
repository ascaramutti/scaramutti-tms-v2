import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { axe } from 'vitest-axe'
import { ServiceDetailPage } from './ServiceDetailPage'
import { AuthProvider } from '../../../shared/auth/AuthContext'
import { currentUserQueryKey } from '../../../shared/auth/queryKeys'
import { tokenStorage } from '../../../shared/auth/tokenStorage'
import type { ServiceDetailResponse, UserRole } from '../../../api'
import { fakeUser } from '../../../test/mocks/handlers/auth'
import { server } from '../../../test/mocks/server'
import { SERVICE_STATUS_PRESENTATION } from '../status/serviceStatusPresentation'
import {
  fakeServiceDetail,
  fakeServiceEvent,
  serviceDetailError,
  serviceDetailOk,
  serviceDetailSlow,
} from '../../../test/mocks/handlers/operations'

function renderDetail({ role = 'admin' as UserRole, path = '/cotizaciones/operaciones/servicios/77' } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  tokenStorage.setTokens('fake-access', 'fake-refresh')
  queryClient.setQueryData(currentUserQueryKey, { ...fakeUser, role })
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route
              path="/cotizaciones/operaciones/servicios/:id"
              element={<ServiceDetailPage />}
            />
            <Route path="/cotizaciones/operaciones" element={<div>Listado de servicios</div>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/** Sube del rótulo del campo a su `<div>`, para acotar asserts a un dato. */
async function fieldValue(label: string): Promise<string> {
  const term = await screen.findByText(label)
  const field = term.parentElement as HTMLElement
  return (field.querySelector('dd') as HTMLElement).textContent ?? ''
}

/**
 * La ficha con ese nombre accesible.
 *
 * Va por el ROL y no subiendo desde el texto del encabezado: una `<section>` es
 * un landmark solo si tiene nombre, y sin el `aria-labelledby` que se lo da
 * dejaría de ser una `region` sin que axe dijera nada (no tiene regla para eso).
 * Buscarla así ata de paso el nombre de las ocho tarjetas de la pantalla.
 */
function cardOf(name: string): HTMLElement {
  return screen.getByRole('region', { name })
}

describe('ServiceDetailPage', () => {
  it('muestra el viaje con su código, su cliente y su estado', async () => {
    server.use(serviceDetailOk())
    renderDetail()

    expect(await screen.findByText('SRV-0077')).toBeInTheDocument()
    expect(screen.getByText(/IPH S\.A\.C\./)).toBeInTheDocument()
    expect(screen.getByText(/RUC 20123456789/)).toBeInTheDocument()
    expect(screen.getByText('Pendiente de asignación')).toBeInTheDocument()
    expect(await fieldValue('Origen')).toBe('Piura')
    expect(await fieldValue('Destino')).toBe('Lima — Callao')
    expect(await fieldValue('Ámbito')).toBe('Provincia')
    expect(await fieldValue('Tipo de carga')).toBe('CARGA GENERAL')
    // Con separador de miles, como el resto del sistema: el valor exacto y no un
    // `toMatch` parcial, que no distinguiría "28,000" de "28000".
    expect(await fieldValue('Peso')).toBe('28,000 kg')
    // La fecha tentativa es date-only y por eso NO pasa por los formateadores con
    // zona: leída como medianoche UTC, en Lima mostraría el 09. El valor exacto es
    // lo único que detecta que alguien la mande al formateador equivocado.
    expect(await fieldValue('Fecha tentativa')).toBe('10/09/2026')
  })

  it('muestra la última actualización al pie de la bitácora, con su hora', async () => {
    // Vive con la bitácora y no entre los datos del viaje: es un hecho sobre el
    // rastro. Con hora, porque dos ediciones del mismo día tienen que distinguirse.
    // El fixture la hace distinta del `createdAt` a propósito: con la misma fecha
    // en los dos, mostrar una bajo el rótulo de la otra no rompería nada.
    server.use(serviceDetailOk())
    renderDetail()
    await screen.findByText('SRV-0077')

    expect(
      within(cardOf('Bitácora')).getByText('Última actualización: 26/08/2026, 08:20'),
    ).toBeInTheDocument()
  })

  it('dice quién registró el viaje aunque no tenga bitácora', async () => {
    // El caso que importa: los viajes migrados que llegan sin ninguna entrada. Si
    // el nombre viviera en la bitácora, para estos no aparecería en ningún lado, y
    // son justo los que están pendientes de asignación y alguien abre para actuar.
    server.use(serviceDetailOk(fakeServiceDetail({ events: [] })))
    renderDetail()
    await screen.findByText('SRV-0077')

    expect(screen.getByText(/por Carlos Scaramutti/)).toBeInTheDocument()
    // Y no se repite abajo como un campo más de las fichas.
    expect(screen.queryByText('Registró')).not.toBeInTheDocument()
  })

  it('dice quién registró el viaje también cuando sí tiene bitácora', async () => {
    server.use(
      serviceDetailOk(
        fakeServiceDetail({ events: [fakeServiceEvent({ note: 'Servicio registrado' })] }),
      ),
    )
    renderDetail()
    await screen.findByText('SRV-0077')

    expect(screen.getByText(/por Carlos Scaramutti/)).toBeInTheDocument()
    expect(screen.queryByText('Registró')).not.toBeInTheDocument()
  })

  it('junta las tres fechas del viaje en una sola ficha', async () => {
    // La prometida y las dos que ocurrieron. Separadas, saber si el viaje salió
    // cuando se dijo obligaba a mirar dos fichas.
    server.use(
      serviceDetailOk(
        fakeServiceDetail({
          status: 'COMPLETED',
          startDateTime: '2026-08-25T02:30:00Z',
          endDateTime: '2026-08-26T18:00:00Z',
        }),
      ),
    )
    renderDetail()
    await screen.findByText('SRV-0077')

    const fechas = cardOf('Fechas')
    for (const label of ['Fecha tentativa', 'Inicio real', 'Fin real']) {
      expect(within(fechas).getByText(label)).toBeInTheDocument()
    }
    // La tentativa ocupa la fila entera: es la que se prometió, y va sobre las dos
    // que ocurrieron en vez de compartir renglón con una de ellas.
    expect(within(fechas).getByText('Fecha tentativa').parentElement).toHaveClass(
      'sm:col-span-2',
    )
    // Y no quedó en la ficha del itinerario, que es de donde salió.
    expect(within(cardOf('Viaje')).queryByText('Fecha tentativa')).not.toBeInTheDocument()
  })

  it('anuncia cada ficha como una sección con su propio nombre', async () => {
    server.use(
      serviceDetailOk(
        fakeServiceDetail({ observations: 'Coordinar con el cliente', events: [fakeServiceEvent()] }),
      ),
    )
    renderDetail()
    await screen.findByText('SRV-0077')

    for (const name of [
      'Viaje',
      'Carga',
      'Precio',
      'Fechas',
      'Observaciones',
      'Recursos asignados',
      'Refuerzos',
      'Bitácora',
    ]) {
      expect(screen.getByRole('region', { name })).toBeInTheDocument()
    }
    // Ni una más: cada ficha extra es un lugar más donde buscar el mismo dato.
    expect(screen.getAllByRole('region')).toHaveLength(8)
  })

  it('nombra en la bajada al cliente, su RUC y el día en que se registró', async () => {
    // Texto EXACTO, no un `toMatch` parcial: un regex sin ancla de fin no distingue
    // "24/08/2026" de "24/08/2026, 21:00", así que el formateador se podría cambiar
    // por el que trae hora sin que nada fallara. Acá la bajada es una línea de
    // presentación y el día alcanza; la hora vive en la ficha.
    // Y el fixture cruza medianoche a propósito (25/08 02:00 UTC es el 24 en Lima),
    // así que la misma línea también detecta que se saque la zona horaria.
    server.use(serviceDetailOk(fakeServiceDetail({ createdAt: '2026-08-25T02:00:00Z' })))
    renderDetail()
    await screen.findByText('SRV-0077')

    expect(screen.getByText(/registrado el/).textContent).toBe(
      'IPH S.A.C. · RUC 20123456789 · registrado el 24/08/2026 por Carlos Scaramutti',
    )
  })

  // ----- El veto de importes del despacho (RN-OP8) -----
  it('al despacho no le muestra el precio NI UNA VEZ en toda la pantalla', async () => {
    // El servidor le OMITE price y currencyCode; se reproduce igual, borrando las
    // claves en vez de ponerlas en null.
    const service = fakeServiceDetail({
      events: [fakeServiceEvent({ note: 'Servicio registrado' })],
    })
    delete service.price
    delete service.currencyCode
    server.use(serviceDetailOk(service))

    renderDetail({ role: 'dispatcher' })
    await screen.findByText('SRV-0077')

    // Medido sobre lo RENDERIZADO, no sobre una prop: el importe del fixture es
    // 5800, y no puede aparecer de ninguna forma ni con ningún formato.
    const rendered = document.body.textContent ?? ''
    expect(rendered).not.toMatch(/5[.,]?800/)
    expect(rendered).not.toMatch(/S\/|PEN/)
    expect(screen.queryByText('Precio')).not.toBeInTheDocument()
    expect(screen.queryByText('Acordado')).not.toBeInTheDocument()
  })

  it('al despacho le ofrece asignar los recursos del viaje', async () => {
    server.use(serviceDetailOk())
    renderDetail({ role: 'dispatcher' })
    await screen.findByText('SRV-0077')

    // Es el único caso que ata `canOperateService` con el botón: los tests de la
    // ficha reciben el permiso ya resuelto como prop, así que miden la plomería y
    // no la política. Sin esto, cablear la prop a `true` no rompe nada.
    expect(
      await screen.findByRole('button', { name: /asignar recursos/i }),
    ).toBeInTheDocument()
  })

  it('a ventas no le ofrece asignar, y le deja la ficha entera', async () => {
    server.use(serviceDetailOk())
    renderDetail({ role: 'sales' })
    await screen.findByText('SRV-0077')

    // La otra dirección, y con un rol que SÍ entra a la pantalla: se le saca la
    // acción, no el dato.
    expect(screen.queryByRole('button', { name: /asignar recursos/i })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Recursos asignados' })).toBeInTheDocument()
  })

  it('a quien sí puede verlo le muestra el importe con su moneda', async () => {
    server.use(serviceDetailOk())
    renderDetail({ role: 'sales' })
    await screen.findByText('SRV-0077')

    // Texto EXACTO, con el símbolo: un regex sobre el número solo no distingue
    // `formatCurrency` de un formateador de cantidades, así que la moneda (que es
    // la mitad del dato) quedaba medida solo por su lado negativo, en el caso del
    // despacho.
    // El separador entre el símbolo y el número es un espacio DURO (U+00A0), que
    // es lo que emite `Intl`: escrito como espacio común el test falla con dos
    // cadenas que en pantalla se ven idénticas.
    expect(await fieldValue('Acordado')).toBe('S/\u00a05,800.00')
  })

  it('degrada a guion un precio sin su moneda, en vez de romper', async () => {
    // El contrato los declara opcionales por separado, aunque el servidor los
    // omita juntos. Sin este caso la rama del guion no la alcanza ninguna prueba y
    // podría borrarse sin que nada avise.
    const service = fakeServiceDetail()
    delete service.currencyCode
    server.use(serviceDetailOk(service))
    renderDetail({ role: 'sales' })
    await screen.findByText('SRV-0077')

    expect(await fieldValue('Acordado')).toBe('—')
  })

  it('degrada a guion una moneda sin su importe', async () => {
    // La otra mitad de la misma guarda. El caso de arriba cubría solo la del
    // `currencyCode`, así que la del `price` se podía borrar sin que nada avisara
    // y `formatCurrency(undefined, 'PEN')` habría quedado a la vista.
    const service = fakeServiceDetail()
    delete service.price
    server.use(serviceDetailOk(service))
    renderDetail({ role: 'sales' })
    await screen.findByText('SRV-0077')

    expect(await fieldValue('Acordado')).toBe('—')
  })

  // ----- Los datos reales, que vienen incompletos -----
  it('muestra las medidas que hay y no inventa las que faltan', async () => {
    // El fixture trae largo y no ancho ni alto: 10 de los 905 viajes migrados
    // tienen esa forma exacta, y 171 llegan con alguna medida faltante.
    server.use(serviceDetailOk())
    renderDetail()

    expect(await fieldValue('Medidas')).toBe('Largo 12.5 m')
  })

  it('las fichas de datos colapsan a una columna en pantalla angosta', async () => {
    // En un teléfono, dos columnas dentro de una tarjeta parten los valores por la
    // mitad: es el mismo corte que motivó darle la fila entera a las medidas. Se
    // mide la grilla de cada ficha, que es donde vive la decisión.
    server.use(serviceDetailOk())
    renderDetail()
    await screen.findByText('SRV-0077')

    // Se sube desde un rótulo de cada ficha hasta su `<dl>`: un `dl` no expone rol
    // de lista, así que no hay forma de pedirlo por rol.
    for (const label of ['Origen', 'Tipo de carga', 'Fecha tentativa']) {
      const grilla = screen.getByText(label).closest('dl')
      expect(grilla).toHaveClass('grid-cols-1')
      expect(grilla).toHaveClass('sm:grid-cols-2')
    }
  })

  it('da a las medidas la fila entera, para que el texto no se parta', async () => {
    // Es lo que se ve en pantalla y por eso se pidió: en media celda el salto caía
    // en cualquier lado, incluso entre un número y su unidad ("2.85 / m"). El
    // rótulo y su valor son hermanos dentro del mismo `div`, así que la clase del
    // ancho vive ahí y no en un envoltorio (un `dl` solo admite `div` directo).
    server.use(serviceDetailOk())
    renderDetail()
    const medidas = await screen.findByText('Medidas')

    expect(medidas.parentElement).toHaveClass('sm:col-span-2')
  })

  it('separa los miles en las TRES medidas, no solo en la primera', async () => {
    // Las tres con cuatro cifras a propósito: con valores chicos (2.4, 3) el texto
    // sale igual con formateador y sin él, así que un caso así no distingue nada.
    // Es el mismo descuido que el `toMatch` parcial, con otra ropa.
    server.use(
      serviceDetailOk(fakeServiceDetail({ lengthM: 1200.5, widthM: 2400, heightM: 3600.25 })),
    )
    renderDetail()

    expect(await fieldValue('Medidas')).toBe('Largo 1,200.5 m · Ancho 2,400 m · Alto 3,600.25 m')
  })

  it('muestra un guion cuando el viaje no tiene ninguna medida', async () => {
    server.use(serviceDetailOk(fakeServiceDetail({ lengthM: null, widthM: null, heightM: null })))
    renderDetail()

    expect(await fieldValue('Medidas')).toBe('—')
  })

  it('muestra las tres medidas cuando están todas', async () => {
    server.use(serviceDetailOk(fakeServiceDetail({ lengthM: 12.5, widthM: 2.4, heightM: 3 })))
    renderDetail()

    expect(await fieldValue('Medidas')).toBe('Largo 12.5 m · Ancho 2.4 m · Alto 3 m')
  })

  it('deja en guion las fechas reales de un viaje que todavía no arrancó', async () => {
    // 80 de los 905 viajes migrados están sin arrancar: el guion es la respuesta
    // correcta, no una falla de carga.
    server.use(serviceDetailOk())
    renderDetail()

    expect(await fieldValue('Inicio real')).toBe('—')
    expect(await fieldValue('Fin real')).toBe('—')
  })

  // ----- Observaciones -----
  it('no dibuja la sección de observaciones cuando el viaje no tiene', async () => {
    // 867 de los 905 viajes migrados llegan sin ellas: una tarjeta vacía sería lo que más
    // se ve en la pantalla y no diría nada.
    server.use(serviceDetailOk())
    renderDetail()
    await screen.findByText('SRV-0077')

    expect(screen.queryByText('Observaciones')).not.toBeInTheDocument()
  })

  it('respeta los saltos INTERNOS de las observaciones y recorta los de los bordes', async () => {
    // Se cargan en un campo de varias líneas (`Textarea` del alta), así que el
    // salto interno es parte del dato. Los de los bordes no separan nada y solo
    // dibujarían una línea vacía, igual que en la bitácora.
    //
    // El fixture trae los DOS casos reales de la migración a la vez: un salto al
    // final y un espacio al final. Sin ellos el texto se vería igual con recorte
    // y sin él, y el caso no distinguiría nada.
    const observations = '\nCarga sobredimensionada.\nCoordinar con el cliente antes de salir.\n  '
    server.use(serviceDetailOk(fakeServiceDetail({ observations })))
    renderDetail()

    const paragraph = await screen.findByText(/Carga sobredimensionada/)
    expect(paragraph).toHaveClass('whitespace-pre-line')
    expect(paragraph.textContent).toBe(
      'Carga sobredimensionada.\nCoordinar con el cliente antes de salir.',
    )
  })

  it('no dibuja la sección si las observaciones son solo espacios', async () => {
    // El alta de v2 no puede producirlo (el schema recorta), pero un dato migrado
    // sí: una sección con un párrafo en blanco es peor que no tenerla.
    server.use(serviceDetailOk(fakeServiceDetail({ observations: '  \n\n ' })))
    renderDetail()
    await screen.findByText('SRV-0077')

    expect(screen.queryByText('Observaciones')).not.toBeInTheDocument()
  })

  it('muestra las fechas reales en hora de Lima cuando el viaje ya cerró', async () => {
    server.use(
      serviceDetailOk(
        fakeServiceDetail({
          status: 'COMPLETED',
          // 25/08 02:30 UTC es todavía el 24 en Lima: si alguien saca la zona del
          // formateador, este test lo ve.
          startDateTime: '2026-08-25T02:30:00Z',
          endDateTime: '2026-08-26T18:00:00Z',
        }),
      ),
    )
    renderDetail()

    // Con la HORA: para operaciones, un viaje que arrancó a las 06:00 y uno que
    // arrancó a las 21:30 no son lo mismo, y el dato viene en la respuesta. El
    // valor exacto (no un `toMatch` parcial) es lo único que detecta que se
    // vuelva a un formateador que descarta la hora.
    expect(await fieldValue('Inicio real')).toBe('24/08/2026, 21:30')
    expect(await fieldValue('Fin real')).toBe('26/08/2026, 13:00')
  })

  // ----- Recursos y refuerzos -----
  it('muestra en guion los recursos de un viaje sin asignar', async () => {
    server.use(serviceDetailOk())
    renderDetail()

    expect(await fieldValue('Conductor')).toBe('—')
    expect(await fieldValue('Tracto')).toBe('—')
    expect(await fieldValue('Carreta')).toBe('—')
  })

  it('muestra los recursos asignados, con la carreta en guion si no lleva', async () => {
    server.use(
      serviceDetailOk(
        fakeServiceDetail({
          status: 'IN_PROGRESS',
          driver: { id: 3, fullName: 'Juan Pérez' },
          tractor: { kind: 'TRACTOR', id: 9, plate: 'ABC-123' },
          trailer: null,
        }),
      ),
    )
    renderDetail()

    expect(await fieldValue('Conductor')).toBe('Juan Pérez')
    expect(await fieldValue('Tracto')).toBe('ABC-123')
    // Opcional por contrato: hay carga que no lleva carreta.
    expect(await fieldValue('Carreta')).toBe('—')
  })

  it('dice que no hay refuerzos, que es el caso normal', async () => {
    // Ninguno de los 905 viajes migrados tiene refuerzos: esta es la pantalla que
    // se va a ver casi siempre.
    server.use(serviceDetailOk())
    renderDetail()

    expect(
      await screen.findByText('Este viaje no tiene recursos de refuerzo.'),
    ).toBeInTheDocument()
  })

  it('lista un refuerzo con sus recursos, su motivo y quién lo sumó', async () => {
    server.use(
      serviceDetailOk(
        fakeServiceDetail({
          status: 'IN_PROGRESS',
          additionalResources: [
            {
              id: 5,
              driver: { id: 8, fullName: 'Ana Ríos' },
              tractor: { kind: 'TRACTOR', id: 4, plate: 'XYZ-987' },
              trailer: null,
              reason: 'Relevo por descanso reglamentario del conductor principal',
              assignedBy: { id: 2, username: 'jvega', fullName: 'Julia Vega' },
              assignedAt: '2026-08-24T02:00:00Z',
            },
          ],
        }),
      ),
    )
    renderDetail()
    await screen.findByText('SRV-0077')

    const card = cardOf('Refuerzos')
    // Rotulado: dos placas sin rótulo no dirían cuál es cuál.
    expect(await within(card).findByText('Ana Ríos · Tracto XYZ-987')).toBeInTheDocument()
    expect(
      within(card).getByText('Relevo por descanso reglamentario del conductor principal'),
    ).toBeInTheDocument()
    // Con hora: en ruta se pueden sumar varios refuerzos el mismo día y el orden
    // entre ellos es lo que se está mirando.
    expect(within(card).getByText('Julia Vega · 23/08/2026, 21:00')).toBeInTheDocument()
    // La carreta no participó del pedido: no se nombra en vez de ir como guion.
    expect(within(card).queryByText(/—/)).not.toBeInTheDocument()
  })

  it('rotula las dos placas de un refuerzo que trae tracto y carreta', async () => {
    // Con las dos, sin rótulo quedarían dos placas seguidas e indistinguibles. El
    // caso de arriba no lo cubre porque su carreta va en null.
    server.use(
      serviceDetailOk(
        fakeServiceDetail({
          status: 'IN_PROGRESS',
          additionalResources: [
            {
              id: 7,
              driver: { id: 8, fullName: 'Ana Ríos' },
              tractor: { kind: 'TRACTOR', id: 4, plate: 'XYZ987' },
              trailer: { kind: 'TRAILER', id: 5, plate: 'QWE456' },
              reason: 'Cambio de unidad por desperfecto en ruta',
              assignedBy: { id: 2, username: 'jvega', fullName: 'Julia Vega' },
              assignedAt: '2026-08-24T02:00:00Z',
            },
          ],
        }),
      ),
    )
    renderDetail()
    await screen.findByText('SRV-0077')

    expect(
      within(cardOf('Refuerzos')).getByText('Ana Ríos · Tracto XYZ987 · Carreta QWE456'),
    ).toBeInTheDocument()
  })

  it('no deja una línea vacía si un refuerzo llegara sin ningún recurso', async () => {
    // El contrato tipa los tres como opcionales de a uno y no promete que venga
    // alguno. Hoy el endpoint que los crea exige al menos uno, así que esta rama
    // es defensiva: sin el caso, se puede borrar el guion sin que nada avise.
    server.use(
      serviceDetailOk(
        fakeServiceDetail({
          status: 'IN_PROGRESS',
          additionalResources: [
            {
              id: 6,
              driver: null,
              tractor: null,
              trailer: null,
              reason: 'Pedido sin recursos, que el servidor no debería producir',
              assignedBy: { id: 2, username: 'jvega', fullName: 'Julia Vega' },
              assignedAt: '2026-08-24T02:00:00Z',
            },
          ],
        }),
      ),
    )
    renderDetail()
    await screen.findByText('SRV-0077')

    const card = cardOf('Refuerzos')
    expect(within(card).getByText('—')).toBeInTheDocument()
  })

  // ----- La bitácora, dentro de la pantalla -----
  it('muestra la bitácora del viaje', async () => {
    server.use(
      serviceDetailOk(
        fakeServiceDetail({
          events: [
            fakeServiceEvent({ id: 1, note: 'Servicio registrado' }),
            fakeServiceEvent({ id: 2, eventType: 'ASSIGNMENT', note: 'Recursos asignados' }),
          ],
        }),
      ),
    )
    renderDetail()
    await screen.findByText('SRV-0077')

    const card = cardOf('Bitácora')
    expect(await within(card).findByText('Servicio registrado')).toBeInTheDocument()
    expect(within(card).getByText('Recursos asignados')).toBeInTheDocument()
  })

  // ----- Estados de carga y de error -----
  it('muestra el estado de carga mientras llega el servicio', async () => {
    server.use(serviceDetailSlow())
    renderDetail()

    expect(screen.getByLabelText('Cargando servicio')).toBeInTheDocument()
    expect(await screen.findByText('SRV-0077')).toBeInTheDocument()
  })

  it('trata un servicio inexistente como no encontrado, sin ofrecer reintentar', async () => {
    server.use(serviceDetailError(404, { code: 'OPS-005', detail: 'El servicio no existe.' }))
    renderDetail()

    expect(await screen.findByText('No se encontró el servicio')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument()
  })

  it('trata como no encontrado un id que no es positivo', async () => {
    // `Number.isInteger(0)` es true, así que el cero pasa esa guarda: sin la de
    // "mayor que cero", `/servicios/0` cae en un hook deshabilitado y la pantalla
    // termina en la rama de error genérica, ofreciendo reintentar algo que nunca se
    // va a pedir, en vez de decir que ese id no existe.
    for (const path of [
      '/cotizaciones/operaciones/servicios/0',
      '/cotizaciones/operaciones/servicios/-5',
    ]) {
      const { unmount } = renderDetail({ path })
      expect(await screen.findByText('No se encontró el servicio')).toBeInTheDocument()
      unmount()
    }
  })

  it('con un id que no es un número no le pregunta al servidor', async () => {
    // "abc" entra a la ruta igual: el patrón `:id` no distingue. La pantalla lo
    // resuelve sin gastar una request que el backend contestaría 400 COM-001.
    let calls = 0
    server.use(
      http.get('http://localhost:8080/api/v1/services/:id', () => {
        calls += 1
        return HttpResponse.json(fakeServiceDetail())
      }),
    )
    renderDetail({ path: '/cotizaciones/operaciones/servicios/abc' })

    expect(await screen.findByText('No se encontró el servicio')).toBeInTheDocument()
    expect(calls).toBe(0)
  })

  it('muestra el motivo del servidor cuando la carga falla, y deja reintentar', async () => {
    server.use(serviceDetailError(500, { detail: 'La base de datos no responde.' }))
    renderDetail()

    // El hook reintenta una vez ante un 500 (no ante un 404), así que el mensaje
    // tarda más que el timeout de 1s que trae `findByText` por defecto.
    expect(
      await screen.findByText('La base de datos no responde.', {}, { timeout: 3000 }),
    ).toBeInTheDocument()

    server.use(serviceDetailOk())
    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('SRV-0077')).toBeInTheDocument()
  })

  it('vuelve al listado desde el enlace de arriba', async () => {
    server.use(serviceDetailOk())
    renderDetail()
    await screen.findByText('SRV-0077')

    await userEvent.click(screen.getByRole('link', { name: /Volver a servicios/ }))
    expect(await screen.findByText('Listado de servicios')).toBeInTheDocument()
  })

  it('no tiene violaciones de accesibilidad', async () => {
    server.use(
      serviceDetailOk(
        fakeServiceDetail({ events: [fakeServiceEvent()] }) as ServiceDetailResponse,
      ),
    )
    const { container } = renderDetail()
    await screen.findByText('SRV-0077')

    expect(await axe(container)).toHaveNoViolations()
  })
})

describe('ServiceDetailPage, las acciones de estado', () => {
  it('ofrece iniciar junto al badge de un viaje pendiente de inicio', async () => {
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'PENDING_START' })))
    renderDetail()

    expect(await screen.findByRole('button', { name: /Iniciar viaje/ })).toBeInTheDocument()
    expect(screen.getByText('Pendiente de inicio')).toBeInTheDocument()
  })

  it('ofrece finalizar en un viaje en ruta', async () => {
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'IN_PROGRESS' })))
    renderDetail()

    expect(await screen.findByRole('button', { name: /Finalizar viaje/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Iniciar viaje/ })).not.toBeInTheDocument()
  })

  it('no ofrece transiciones en un viaje completado, y el badge sigue estando', async () => {
    // El único estado del circuito sin salidas. Corregirlo SÍ se ofrece: el contrato dice
    // que un viaje ya cerrado se edita. El badge NO se va con los botones: el usuario
    // tiene que seguir sabiendo en qué estado quedó el viaje.
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'COMPLETED' })))
    renderDetail()

    await screen.findByText('SRV-0077')
    const barra = screen.getByRole('group', { name: 'Acciones del viaje' })
    expect(within(barra).queryByRole('button')).not.toBeInTheDocument()
    expect(within(barra).getByRole('link', { name: 'Editar' })).toBeInTheDocument()
    expect(screen.getByText(SERVICE_STATUS_PRESENTATION.COMPLETED.label)).toBeInTheDocument()
  })

  it.each(['CANCELLED', 'DELETED'] as const)(
    'ofrece reabrir en %s, que dejó de ser el final del camino',
    async (status) => {
      server.use(serviceDetailOk(fakeServiceDetail({ status })))
      renderDetail()

      expect(await screen.findByRole('button', { name: /Reabrir viaje/ })).toBeInTheDocument()
      expect(screen.getByText(SERVICE_STATUS_PRESENTATION[status].label)).toBeInTheDocument()
    },
  )

  it('a ventas le saca las transiciones, pero corrige el viaje y ve los datos', async () => {
    // Ventas registra y corrige viajes, y no los opera: no los inicia, no los finaliza y
    // no los saca del circuito. Las tres mitades, porque cada una tapa un error distinto:
    // sin la primera, dejarle operar pasaría; sin la segunda, quitarle también la edición
    // pasaría; sin la tercera, esconderle la pantalla entera pasaría.
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'IN_PROGRESS' })))
    renderDetail({ role: 'sales' })

    await screen.findByText('SRV-0077')
    const barra = screen.getByRole('group', { name: 'Acciones del viaje' })
    expect(within(barra).queryByRole('button')).not.toBeInTheDocument()
    expect(within(barra).getByRole('link', { name: 'Editar' })).toBeInTheDocument()
    expect(screen.getByText('En ruta')).toBeInTheDocument()
    expect(screen.getByText('Bitácora')).toBeInTheDocument()
  })

  it('al despacho no le ofrece corregir el viaje', async () => {
    // El cuerpo de la edición obliga a mandar el precio, que es justo lo que a ese rol se
    // le oculta: el servidor le contestaría 403. Sí sigue operando el viaje, que es lo
    // suyo, y por eso se afirman las dos cosas.
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'IN_PROGRESS' })))
    renderDetail({ role: 'dispatcher' })

    await screen.findByText('SRV-0077')
    const barra = screen.getByRole('group', { name: 'Acciones del viaje' })
    expect(within(barra).queryByRole('link', { name: 'Editar' })).not.toBeInTheDocument()
    expect(within(barra).getByRole('button', { name: /Finalizar viaje/ })).toBeInTheDocument()
  })

  it('al despacho le ofrece finalizar el viaje', async () => {
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'IN_PROGRESS' })))
    renderDetail({ role: 'dispatcher' })

    expect(await screen.findByRole('button', { name: /Finalizar viaje/ })).toBeInTheDocument()
  })

  it('no desplaza ni duplica las acciones de recursos', async () => {
    // Lo que NO cambió: la barra nueva se suma al encabezado y las acciones de recursos
    // siguen donde estaban. Montarla encima, o dejarla duplicada al reordenar el
    // layout, es la mutación más barata de cometer y la que ninguna suite de delta ve.
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'PENDING_ASSIGNMENT' })))
    renderDetail()

    expect(await screen.findAllByRole('button', { name: /Asignar recursos/ })).toHaveLength(1)
  })

  it('pone el estado y sus acciones en una fila propia, fuera del encabezado', async () => {
    // Es el defecto que se corrigió: dentro del slot de acción del `PageHeader`, el
    // bloque se acomoda al lado del título mientras entra y baja cuando no, así que la
    // pantalla se veía distinta según el ancho de la ventana. Se afirma la ESTRUCTURA
    // porque el ancho no existe en jsdom: un caso que solo mire "los botones están"
    // pasa igual con el layout viejo puesto.
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'IN_PROGRESS' })))
    const { container } = renderDetail()

    await screen.findByText('SRV-0077')
    const header = container.querySelector('header')
    const actions = screen.getByRole('group', { name: 'Acciones del viaje' })

    expect(header).not.toBeNull()
    expect(header).not.toContainElement(actions)
    expect(header).not.toContainElement(screen.getByText('En ruta'))
  })

  it('alinea el estado a la izquierda y las acciones a la derecha, en la misma fila', async () => {
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'IN_PROGRESS' })))
    renderDetail()

    const badge = await screen.findByText('En ruta')
    const actions = screen.getByRole('group', { name: 'Acciones del viaje' })
    const row = badge.parentElement

    // Comparten fila, y esa fila los separa a los extremos. `flex-wrap` es lo que deja
    // que los botones bajen en anchos chicos sin pisar al badge.
    expect(row).toContainElement(actions)
    expect(row?.className).toContain('justify-between')
    expect(row?.className).toContain('flex-wrap')
  })

  it('tampoco las duplica en el estado donde conviven más botones', async () => {
    // "En ruta" es el caso apretado: el encabezado ofrece dos acciones de estado y la
    // ficha de recursos las suyas. El estado anterior tiene un solo botón de cada lado,
    // así que no distingue un layout que se pisa de uno que no.
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'IN_PROGRESS' })))
    renderDetail()

    expect(await screen.findAllByRole('button', { name: /Agregar refuerzo/ })).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: /Finalizar viaje/ })).toHaveLength(1)
    expect(screen.getAllByRole('button', { name: /Cancelar viaje/ })).toHaveLength(1)
  })

  it('ofrece cancelar también en un viaje sin recursos asignados', async () => {
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'PENDING_ASSIGNMENT' })))
    renderDetail()

    expect(await screen.findByRole('button', { name: /Cancelar viaje/ })).toBeInTheDocument()
  })

  it('al despacho no le ofrece cancelar, pero sí finalizar', async () => {
    // Las dos mitades: sin la segunda, esconderle toda la barra al despacho pasaría.
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'IN_PROGRESS' })))
    renderDetail({ role: 'dispatcher' })

    expect(await screen.findByRole('button', { name: /Finalizar viaje/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Cancelar viaje/ })).not.toBeInTheDocument()
  })

  it('abre el diálogo de iniciar desde el encabezado', async () => {
    const user = userEvent.setup()
    server.use(serviceDetailOk(fakeServiceDetail({ status: 'PENDING_START' })))
    renderDetail()

    await user.click(await screen.findByRole('button', { name: /Iniciar viaje/ }))

    expect(await screen.findByRole('dialog', { name: 'Iniciar viaje' })).toBeInTheDocument()
  })
})
