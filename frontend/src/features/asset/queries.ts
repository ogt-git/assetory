import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  fetchAssetChanges,
  fetchAssetTrend,
  fetchPortfolio,
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
