import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { UserAccountCandidateResponse, UserAccountTemporaryPasswordResponse } from '../../../api'
import { workerKeys } from '../../workers/queryKeys'
import { ConfirmActionDialog } from '../../../shared/ui/ConfirmActionDialog'
import { PersonNameRow } from '../../../shared/ui/PersonNameRow'
import { useCreateUserAccount } from '../hooks/useCreateUserAccount'
import { userAccountKeys } from '../queryKeys'
import { createAccountErrorMessage, rejectsWorker } from '../userAccountApiErrors'

interface CreateAccountDialogProps {
  candidate: UserAccountCandidateResponse
  onClose: () => void
  onCreated: (created: UserAccountTemporaryPasswordResponse) => void
}

export function CreateAccountDialog({ candidate, onClose, onCreated }: CreateAccountDialogProps) {
  const queryClient = useQueryClient()
  const createAccount = useCreateUserAccount()
  const [error, setError] = useState<string | null>(null)

  function confirm() {
    setError(null)
    createAccount.mutate(
      { workerId: candidate.workerId },
      {
        onSuccess: (created) => {
          // La respuesta trae la temporal. Sin observador, la caché la descartaría recién en la tarea
          // siguiente, con el panel ya a la vista: sale antes de mostrarlo.
          createAccount.reset()
          const mutations = queryClient.getMutationCache()
          mutations.findAll({ mutationKey: userAccountKeys.create() }).forEach((mutation) => mutations.remove(mutation))
          onCreated(created)
        },
        onError: (mutationError) => {
          setError(createAccountErrorMessage(mutationError))
          // El rechazo dice que el trabajador cambió: la ficha guardada puede decir que no tiene usuario.
          if (rejectsWorker(mutationError)) {
            void queryClient.invalidateQueries({ queryKey: userAccountKeys.candidates() })
            void queryClient.invalidateQueries({ queryKey: workerKeys.detail(candidate.workerId), refetchType: 'none' })
          }
        },
      },
    )
  }

  return (
    <ConfirmActionDialog
      title="Crear usuario"
      confirmLabel="Crear usuario"
      pendingLabel="Creando…"
      confirmVariant="success"
      size="md"
      isPending={createAccount.isPending}
      error={error}
      onConfirm={confirm}
      onClose={onClose}
    >
      <p className="text-sm text-fg-body">
        Vas a crear la cuenta de {candidate.fullName} ({candidate.role.description}).
      </p>
      <PersonNameRow
        firstName={candidate.firstName}
        paternalLastName={candidate.paternalLastName}
        maternalLastName={candidate.maternalLastName}
        className="rounded-lg border border-border px-4 py-3"
      />
      <p className="text-sm text-fg-body">
        Se generará el usuario <span className="font-mono">{candidate.proposedUsername}</span> y una contraseña temporal,
        que se muestra una sola vez.
      </p>
    </ConfirmActionDialog>
  )
}
