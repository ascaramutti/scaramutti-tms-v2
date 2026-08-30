import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { ResourceConflictAlert } from './ResourceConflictAlert'
import { THREE_CONFLICTS } from '../../../../test/mocks/handlers/operations'
import type { ServiceOperationError } from '../../utils/serviceResourceConflict'

/** El conflicto forzable, que es el único que arma la tabla y el botón. */
function forzable(): ServiceOperationError {
  return {
    code: 'OPS-002',
    detail: 'El conductor ya está asignado a otro viaje.',
    forcible: true,
    conflicts: [...THREE_CONFLICTS],
  }
}

function renderAlert(forceConsequence?: string) {
  return render(
    <ResourceConflictAlert
      error={forzable()}
      forceLabel="Forzar"
      forceConsequence={forceConsequence}
      isPending={false}
      onForce={vi.fn()}
    />,
  )
}

describe('ResourceConflictAlert', () => {
  it('no cuelga el botón de una descripción que no existe', () => {
    // Es la rama que usan asignar y sumar refuerzos, que NO pasan la consecuencia: sin la
    // guarda, su botón apuntaría a un id ausente. Los `axe` de esas pantallas no lo ven,
    // porque una referencia rota de `aria-describedby` cuenta como incompleta y no como
    // violación.
    renderAlert()

    expect(screen.getByRole('button', { name: 'Forzar' })).not.toHaveAttribute(
      'aria-describedby',
    )
  })

  it('cuelga el botón de la consecuencia cuando la hay', () => {
    renderAlert('Quedan compartidos.')

    expect(screen.getByRole('button', { name: 'Forzar' })).toHaveAccessibleDescription(
      'Quedan compartidos.',
    )
  })

  it('destaca el encabezado por encima de la consecuencia que lo acompaña', () => {
    // El encabezado es el diagnóstico y la consecuencia va debajo con el mismo tamaño y
    // color: sin este peso las dos frases compiten. Se afirma el contraste y no solo el
    // peso del encabezado, porque poner las dos en seminegrita no destaca nada.
    renderAlert('Quedan compartidos.')

    expect(screen.getByRole('alert').className).toContain('font-medium')
    expect(screen.getByText('Quedan compartidos.').className).not.toContain('font-medium')
  })

  it('deja el encabezado como estaba para quien no pasa consecuencia', () => {
    // Asignar y sumar refuerzos ya están en producción y no pidieron el cambio de peso:
    // sin esta rama, el pulido de una pantalla nueva les cambia el aspecto en silencio.
    renderAlert()

    expect(screen.getByRole('alert').className).not.toContain('font-medium')
  })

  it('no deja apretar dos veces mientras el pedido viaja', () => {
    // El botón es la única pieza que sobrevive al primer clic en las pantallas donde el
    // aviso no se desmonta, así que es acá donde el bloqueo tiene que estar.
    render(
      <ResourceConflictAlert
        error={forzable()}
        forceLabel="Forzar"
        isPending
        onForce={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: 'Forzar' })).toBeDisabled()
  })

  it('lista una fila por conflicto, que es la única fuente de verdad', () => {
    renderAlert()

    for (const conflicto of THREE_CONFLICTS) {
      expect(screen.getByText(conflicto.resourceName)).toBeInTheDocument()
    }
    // El encabezado no resume la tabla: un resumen se desalinea con lo que lista debajo.
    expect(within(screen.getByRole('alert')).queryByText(/\d+/)).not.toBeInTheDocument()
  })
})
