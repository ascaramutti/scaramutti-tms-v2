import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { DataTable } from './DataTable'

function renderLoading(loadingLabel?: string) {
  return render(
    <DataTable<{ id: number }>
      columns={[{ key: 'id', header: 'Id' }]}
      data={[]}
      keyExtractor={(row) => row.id}
      page={0}
      size={10}
      total={0}
      totalPages={0}
      onPageChange={() => {}}
      isLoading
      loadingLabel={loadingLabel}
    />,
  )
}

describe('DataTable, carga inicial', () => {
  /** Las tablas que no pasan el texto dependen de este valor: sin test, moverlo las cambia a todas. */
  it('sin texto propio, el spinner se anuncia como Cargando', () => {
    renderLoading()
    expect(screen.getByRole('status', { name: 'Cargando' })).toBeInTheDocument()
  })

  it('con texto propio, el spinner se anuncia con ese texto', () => {
    renderLoading('Buscando trabajadores')
    expect(screen.getByRole('status', { name: 'Buscando trabajadores' })).toBeInTheDocument()
  })
})
