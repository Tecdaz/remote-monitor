export const bedsKey = ['beds'] as const

export const patientKey = (id: string) => ['patients', id] as const

export const measurementsKey = (id: string) => ['measurements', id] as const
