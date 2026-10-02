const PLACEHOLDER = '—'

function isInvalidNumber(value: unknown): value is null | undefined {
  return (
    typeof value !== 'number' ||
    Number.isNaN(value) ||
    !Number.isFinite(value)
  )
}

/** 원화 금액. 예: 1234 -> "₩1,234" */
export function formatKRW(value: number | null | undefined): string {
  if (isInvalidNumber(value)) return PLACEHOLDER
  const abs = Math.abs(Math.round(value))
  const sign = value < 0 ? '-' : ''
  return `${sign}₩${abs.toLocaleString('ko-KR')}`
}

/** 부호 포함 원화 금액. 예: 1234 -> "+₩1,234", -1234 -> "-₩1,234" */
export function formatSignedKRW(value: number | null | undefined): string {
  if (isInvalidNumber(value)) return PLACEHOLDER
  const abs = Math.abs(Math.round(value))
  const sign = value > 0 ? '+' : value < 0 ? '-' : ''
  return `${sign}₩${abs.toLocaleString('ko-KR')}`
}

/** 등락률. 비율 입력. 예: 0.0523 -> "+5.23%" */
export function formatPercent(ratio: number | null | undefined): string {
  if (isInvalidNumber(ratio)) return PLACEHOLDER
  const percent = Math.abs(ratio * 100)
  const sign = ratio > 0 ? '+' : ratio < 0 ? '-' : ''
  return `${sign}${percent.toFixed(2)}%`
}
