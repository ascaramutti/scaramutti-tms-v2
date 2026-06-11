import { useId, type ReactNode } from 'react'

interface SidebarSectionProps {
  label: string
  children: ReactNode
}

export function SidebarSection({ label, children }: SidebarSectionProps) {
  const labelId = useId()
  return (
    <div>
      <h2 id={labelId} className="px-3 mb-1 text-xs font-semibold text-slate-400 uppercase tracking-wider">
        {label}
      </h2>
      <ul aria-labelledby={labelId} className="flex flex-col gap-0.5 list-none p-0 m-0">
        {children}
      </ul>
    </div>
  )
}
