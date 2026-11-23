import { cn } from '../../../shared/utils/cn'
import { Card } from '../../../shared/ui/Card'
import { FIELD_LABEL, fieldClasses } from '../../../shared/ui/fieldClasses'
import { ACCOUNT_STATUS_FILTER_OPTIONS, type AccountStatusFilter } from '../accountStatusFilter'

interface AccountsFilterBarProps {
  status: AccountStatusFilter
  onStatusChange: (next: AccountStatusFilter) => void
  announcement: string
}

export function AccountsFilterBar({ status, onStatusChange, announcement }: AccountsFilterBarProps) {
  return (
    <Card padding="md">
      <div className="grid gap-4 sm:grid-cols-[12rem_1fr] sm:items-end">
        <div>
          <label htmlFor="status" className={FIELD_LABEL}>
            Estado
          </label>
          <select
            id="status"
            value={status}
            onChange={(event) => onStatusChange(event.target.value as AccountStatusFilter)}
            className={cn('w-full', fieldClasses({ density: 'compact' }))}
          >
            {ACCOUNT_STATUS_FILTER_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
        {/* Siempre montada: una región viva que entra al árbol con su texto no se anuncia. */}
        <p aria-live="polite" className="text-sm text-fg-muted">
          {announcement}
        </p>
      </div>
    </Card>
  )
}
