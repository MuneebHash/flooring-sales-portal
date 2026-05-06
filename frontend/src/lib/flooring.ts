export type FlooringType = 'SOFT' | 'HARD'

export const FLOORING_LABELS: Record<FlooringType, string> = {
  SOFT: 'Soft flooring',
  HARD: 'Hard flooring',
}

export const FLOORING_TONES: Record<FlooringType, string> = {
  SOFT: 'bg-rose-50 text-rose-700 border-rose-200',
  HARD: 'bg-emerald-50 text-emerald-700 border-emerald-200',
}
