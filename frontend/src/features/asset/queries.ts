import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  fetchAssetChanges,
  fetchAssetTrend,
  fetchPortfolio,
  fetchSnapshotDetail,
  fetchSnapshotMonth,
  type TrendPeriod,
} from '@/features/asset/api'

/*
 * 대시보드 영역별 조회 — 영역마다 따로 불러와 한 영역의 실패가 다른 영역을 가리지 않는다.
 * 값은 일일 배치가 저장한 기록이라 하루에 한 번 바뀐다 → 화면 전환마다 다시 받지 않게 5분 동안 신선하게 둔다.
 */
const STALE_TIME = 5 * 60 * 1000

export const assetQueryKeys = {
  changes: ['snapshots', 'changes'] as const,
  trend: (period: TrendPeriod) => ['snapshots', 'trend', period] as const,
  portfolio: ['snapshots', 'portfolio'] as const,
  // month 생략(최근 기록 달)은 'latest'로 따로 캐시한다 — 응답의 month로 같은 달 키를 다시 부르면 한 번 더 받는다
  month: (month: string | undefined) =>
    ['snapshots', 'month', month ?? 'latest'] as const,
  detail: (date: string) => ['snapshots', 'detail', date] as const,
}

/** ② 총자산 요약 */
export function useAssetChanges() {
  return useQuery({
    queryKey: assetQueryKeys.changes,
    queryFn: fetchAssetChanges,
    staleTime: STALE_TIME,
  })
}

/** ③ 추이 — 기간 탭을 바꾸는 동안 이전 기간 차트를 유지한다 */
export function useAssetTrend(period: TrendPeriod) {
  return useQuery({
    queryKey: assetQueryKeys.trend(period),
    queryFn: () => fetchAssetTrend(period),
    staleTime: STALE_TIME,
    placeholderData: keepPreviousData,
  })
}

/** ⑤ 자산 구성 · ⑥ 보유 종목 — 같은 키를 공유해 요청은 1회 */
export function usePortfolio() {
  return useQuery({
    queryKey: assetQueryKeys.portfolio,
    queryFn: fetchPortfolio,
    staleTime: STALE_TIME,
  })
}

/** S-02 월 목록 — 월을 바꾸는 동안 이전 달 목록을 유지한다 */
export function useSnapshotMonth(month: string | undefined) {
  return useQuery({
    queryKey: assetQueryKeys.month(month),
    queryFn: () => fetchSnapshotMonth(month),
    staleTime: STALE_TIME,
    placeholderData: keepPreviousData,
  })
}

/** S-02 날짜 상세 — 행을 펼쳤을 때만(enabled) 조회하고, 다시 펼치면 캐시를 쓴다 */
export function useSnapshotDetail(date: string, enabled: boolean) {
  return useQuery({
    queryKey: assetQueryKeys.detail(date),
    queryFn: () => fetchSnapshotDetail(date),
    staleTime: STALE_TIME,
    enabled,
  })
}
