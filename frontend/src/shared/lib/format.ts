import type { SnapshotStatus } from '@/shared/api/types'

const PLACEHOLDER = '—'

function isInvalidNumber(value: unknown): value is null | undefined {
  return (
    typeof value !== 'number' || Number.isNaN(value) || !Number.isFinite(value)
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

/**
 * 퍼센트 값 입력(백엔드 changeRate · profitLossRate · ratio). 소수 2자리.
 * 예: 5.2345 -> "+5.23%", -1.5 -> "-1.50%". 비중처럼 부호가 필요 없으면 { signed: false } -> "14.78%"
 */
export function formatRate(
  percent: number | null | undefined,
  { signed = true }: { signed?: boolean } = {},
): string {
  if (isInvalidNumber(percent)) return PLACEHOLDER
  const fixed = Math.abs(percent).toFixed(2)
  // 반올림해서 0.00이 되면 부호를 붙이지 않는다
  if (!signed || Number(fixed) === 0) return `${fixed}%`
  return `${percent > 0 ? '+' : '-'}${fixed}%`
}

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']

/**
 * 'yyyy-MM-dd' -> "10/29 (목)". 형식이 다르면 '—' (시간대 영향 없게 로컬 날짜로 해석).
 * reference(같은 형식)를 주면 해가 다를 때 연도를 붙인다 -> "2025년 10/29 (수)" — 전년 대비 · 1년 추이처럼 해를 넘는 비교용
 */
export function formatShortDate(
  date: string | null | undefined,
  reference?: string | null,
): string {
  const match = date?.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (!match) return PLACEHOLDER
  const [, y, m, d] = match.map(Number)
  const weekday = WEEKDAYS[new Date(y, m - 1, d).getDay()]
  const short = `${m}/${d} (${weekday})`
  const referenceYear = reference?.match(/^(\d{4})-/)?.[1]
  return referenceYear && Number(referenceYear) !== y
    ? `${y}년 ${short}`
    : short
}

const STATUS_LABELS: Record<SnapshotStatus, string> = {
  COMPLETE: '수집 완료',
  PARTIAL: '일부 누락',
  FAILED: '수집 실패',
}

/** 기록 수집 상태 문구 — PARTIAL "일부 누락", FAILED "수집 실패" */
export function snapshotStatusLabel(status: SnapshotStatus): string {
  return STATUS_LABELS[status]
}
