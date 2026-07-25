import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { BackLink } from '../../../shared/ui/BackLink'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { WithdrawalForm } from '../components/WithdrawalForm'

const WITHDRAWALS_PATH = '/cotizaciones/almacen/retiros'

/** Registro de un retiro. Al guardar abre el detalle del retiro recién creado. */
export function WithdrawalCreatePage() {
  const navigate = useNavigate()

  return (
    <div className="mx-auto max-w-[720px] space-y-6 px-6 py-8">
      <BackLink to={WITHDRAWALS_PATH}>Volver a retiros</BackLink>

      <PageHeader
        title="Registrar retiro"
        description="Salida de stock · descuenta existencias al registrarla."
        divider
      />

      <WithdrawalForm
        onCreated={(withdrawal) => {
          toast.success(
            `Retiro de ${withdrawal.product.name} registrado. El stock ya refleja la salida.`,
          )
          navigate(`${WITHDRAWALS_PATH}/${withdrawal.id}`)
        }}
        onCancel={() => navigate(WITHDRAWALS_PATH)}
      />
    </div>
  )
}
