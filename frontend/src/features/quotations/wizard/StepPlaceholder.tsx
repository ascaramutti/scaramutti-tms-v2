interface StepPlaceholderProps {
  title: string
  description?: string
}

/** Placeholder de un step aún no implementado (steps 2-4, PRs siguientes). */
export function StepPlaceholder({ title, description }: StepPlaceholderProps) {
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 px-6 py-16 text-center">
      <p className="text-sm font-medium text-slate-700">{title}</p>
      <p className="mt-1 text-sm text-slate-500">{description ?? 'Se implementa en un próximo PR.'}</p>
    </div>
  )
}
