export type AccountStatusFilter = 'active' | 'inactive' | 'all'

export const ACCOUNT_STATUS_FILTER_DEFAULT: AccountStatusFilter = 'active'

export const ACCOUNT_STATUS_FILTER_OPTIONS: ReadonlyArray<{
  value: AccountStatusFilter
  label: string
}> = [
  { value: 'active', label: 'Activas' },
  { value: 'inactive', label: 'Inactivas' },
  { value: 'all', label: 'Todas' },
]

const IS_ACTIVE_BY_FILTER: Record<AccountStatusFilter, boolean | undefined> = {
  active: true,
  inactive: false,
  all: undefined,
}

export function isActiveParam(statusFilter: AccountStatusFilter): boolean | undefined {
  return IS_ACTIVE_BY_FILTER[statusFilter]
}
