import type { QueryClient } from '@tanstack/react-query'
import type { UserAccountDetailResponse } from '../../../api'
import { userAccountKeys } from '../queryKeys'

// La lista no está a la vista: se marca vieja sin pedirla. El detalle se siembra para no pedirlo de nuevo.
export async function refreshAfterAccountSaved(queryClient: QueryClient, saved: UserAccountDetailResponse) {
  await queryClient.invalidateQueries({ queryKey: userAccountKeys.all, refetchType: 'none' })
  queryClient.setQueryData(userAccountKeys.detail(saved.id), saved)
}
