import type { ReactNode } from 'react'
import { useIsMutating } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { BackLink } from '../../../shared/ui/BackLink'
import { Button } from '../../../shared/ui/Button'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { WORKERS_BASE, workerDetailPath } from '../../../shared/paths'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { WorkerCreateForm } from '../components/WorkerCreateForm'
import { useDocumentTypes } from '../hooks/useDocumentTypes'
import { useSessionRankLevel } from '../hooks/useSessionRankLevel'
import { workerKeys } from '../queryKeys'
import { assignableRoles } from '../rank'

/**
 * Alta de un trabajador. Sin los dos catálogos no hay formulario: el tipo de
 * documento valida el número, y el cargo decide la licencia y a quién se puede dar
 * de alta.
 */
export function WorkerCreatePage() {
  const navigate = useNavigate()
  const documentTypes = useDocumentTypes()
  const { level, roles } = useSessionRankLevel()

  if (documentTypes.isLoading || roles.isLoading) {
    return (
      <Shell>
        <div className="flex justify-center py-10">
          <Spinner size={28} label="Cargando formulario" className="text-accent" />
        </div>
      </Shell>
    )
  }

  // Solo sin datos: si falla la recarga de un catálogo ya cargado, el formulario sigue con lo escrito.
  if (!documentTypes.data || !roles.data || level === undefined) {
    return (
      <Shell>
        <div role="alert" className="flex flex-col items-center gap-3 py-10 text-center">
          <p className="text-sm text-fg-body">
            {getApiErrorMessage(
              documentTypes.error ?? roles.error,
              'No se pudo cargar el formulario. Intenta de nuevo.',
            )}
          </p>
          <Button
            variant="secondary"
            onClick={() => {
              void documentTypes.refetch()
              void roles.refetch()
            }}
          >
            Reintentar
          </Button>
        </div>
      </Shell>
    )
  }

  return (
    <Shell>
      <WorkerCreateForm
        documentTypes={documentTypes.data}
        roles={roles.data}
        roleOptions={assignableRoles(roles.data, level)}
        onCreated={(created) => {
          toast.success(`Se dio de alta a ${created.firstName} ${created.lastName}.`)
          navigate(workerDetailPath(created.id))
        }}
        onCancel={() => navigate(WORKERS_BASE)}
        reloadCatalog={(catalog) => void (catalog === 'roles' ? roles.refetch() : documentTypes.refetch())}
      />
    </Shell>
  )
}

function Shell({ children }: { children: ReactNode }) {
  // Como Cancelar: con el alta en vuelo, volver queda deshabilitado; al responder abriría la ficha igual.
  const saving = useIsMutating({ mutationKey: workerKeys.create() }) > 0
  return (
    <div className="mx-auto max-w-[860px] space-y-6 px-6 py-8">
      <BackLink to={WORKERS_BASE} disabled={saving}>
        Volver a trabajadores
      </BackLink>
      <PageHeader title="Nuevo trabajador" description="Alta en el padrón de trabajadores." divider />
      {children}
    </div>
  )
}
