import type { ReactNode } from 'react'

interface ChartCardProps {
  title: string
  subtitle?: string
  toolbar?: ReactNode
  meta?: ReactNode
  children: ReactNode
  /** Optional class to control min-height / responsive behaviour. */
  className?: string
}

/**
 * Visual container shared by every chart card so the layout stays
 * consistent across the patient dashboard.
 */
export function ChartCard({
  title,
  subtitle,
  toolbar,
  meta,
  children,
  className,
}: ChartCardProps) {
  return (
    <section
      className={['clinical-card', className].filter(Boolean).join(' ')}
      aria-label={title}
    >
      <header className="flex flex-wrap items-start justify-between gap-3 border-b border-clinical-border px-5 py-3">
        <div className="min-w-0">
          <h2 className="truncate text-base font-semibold text-clinical-ink">
            {title}
          </h2>
          {subtitle ? (
            <p className="mt-0.5 text-xs text-clinical-inkFaint">{subtitle}</p>
          ) : null}
        </div>
        <div className="flex flex-wrap items-center gap-3">
          {toolbar}
          {meta ? (
            <div className="text-xs text-clinical-inkFaint num">{meta}</div>
          ) : null}
        </div>
      </header>
      <div className="p-4">{children}</div>
    </section>
  )
}

export function ChartEmptyState({
  message,
  hint,
}: {
  message: string
  hint?: string
}) {
  return (
    <div
      role="status"
      className="flex h-72 flex-col items-center justify-center gap-1 rounded-md bg-clinical-subtle/60 px-6 text-center"
    >
      <p className="text-sm font-medium text-clinical-inkMuted">{message}</p>
      {hint ? (
        <p className="max-w-sm text-xs text-clinical-inkFaint">{hint}</p>
      ) : null}
    </div>
  )
}