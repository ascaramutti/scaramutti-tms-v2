import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { BackLink } from '../../../shared/ui/BackLink'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { EntryForm } from '../components/EntryForm'

const ENTRIES_PATH = '/cotizaciones/almacen/entradas'

/**
 * Registro de una entrada. Al guardar vuelve al listado: el detalle de la
 * entrada todavía no existe como pantalla.
 */
export function EntryCreatePage() {
  const navigate = useNavigate()

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <BackLink to={ENTRIES_PATH}>Volver a entradas</BackLink>

      <PageHeader
        title="Registrar entrada"
        description="Factura de compra · cada ítem suma stock al registrarla."
        divider
      />

      <EntryForm
        mode="create"
        onCreated={(invoice) => {
          toast.success(
            `Factura ${invoice.invoiceNumber} registrada. El stock ya refleja la entrada.`,
          )
          navigate(ENTRIES_PATH)
        }}
        onCancel={() => navigate(ENTRIES_PATH)}
      />
    </div>
  )
}
