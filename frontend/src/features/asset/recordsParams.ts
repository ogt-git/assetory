import { useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'

/*
 * S-02 자산 기록 URL 상태 — ?month=yyyy-MM · ?date=yyyy-MM-dd
 * - date가 있으면 그 날짜의 달로 연다(대시보드 추이 차트 점 클릭 진입). 그 날짜 행을 펼치는 건 화면이 한다
 * - 둘 다 없으면 month 없이 조회한다 → 백엔드 기본(최근 기록이 있는 달), 화면 기준 달은 응답의 month
 * - 형식이 틀린 값(2026-13, abc, 2026-02-30)은 없는 것으로 보고 기본 동작 — 오류 화면을 띄우지 않는다
 */

const MONTH_PATTERN = /^(\d{4})-(0[1-9]|1[0-2])$/
const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/

export function isValidMonth(
  value: string | null | undefined,
): value is string {
  return value != null && MONTH_PATTERN.test(value)
}

/** 달력에 있는 날짜인지까지 본다(2026-02-30 거부) */
export function isValidDate(value: string | null | undefined): value is string {
  const match = value?.match(DATE_PATTERN)
  if (!match) return false
  const [y, m, d] = [Number(match[1]), Number(match[2]), Number(match[3])]
  const date = new Date(y, m - 1, d)
  return (
    date.getFullYear() === y &&
    date.getMonth() === m - 1 &&
    date.getDate() === d
  )
}

export interface RecordsParams {
  /** 조회할 달. undefined = 백엔드 기본(최근 기록 달) */
  month: string | undefined
  /** 진입 시 펼칠 날짜 */
  focusDate: string | undefined
}

export function parseRecordsParams(params: URLSearchParams): RecordsParams {
  const date = params.get('date')
  if (isValidDate(date)) {
    return { month: date.slice(0, 7), focusDate: date }
  }
  const month = params.get('month')
  return {
    month: isValidMonth(month) ? month : undefined,
    focusDate: undefined,
  }
}

/** 'yyyy-MM'을 delta개월 옮긴다. 예: ('2026-01', -1) -> '2025-12' */
export function shiftMonth(month: string, delta: number): string {
  const [y, m] = month.split('-').map(Number)
  const date = new Date(y, m - 1 + delta, 1)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

/** URL의 달 · 날짜와 월 이동. 월을 옮기면 date 파라미터는 지운다 */
export function useRecordsParams() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { month, focusDate } = parseRecordsParams(searchParams)

  const goToMonth = useCallback(
    (next: string) => setSearchParams({ month: next }),
    [setSearchParams],
  )

  return { month, focusDate, goToMonth }
}
