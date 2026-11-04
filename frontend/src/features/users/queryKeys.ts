export type UserAccountListParams = {
  isActive?: boolean
}

export const userAccountKeys = {
  all: ['users'] as const,
  lists: () => [...userAccountKeys.all, 'list'] as const,
  list: (params: UserAccountListParams) => [...userAccountKeys.lists(), params] as const,
}
