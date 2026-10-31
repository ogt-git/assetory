import { apiClient } from '@/shared/api/client'
import type { ApiResponse, SnapshotStatus } from '@/shared/api/types'

/*
 * 자산 기록 조회 API 응답 타입 — 백엔드 snapshot/dto의 필드를 그대로 옮긴다(이름 · nullable 동일).
 * 날짜는 'yyyy-MM-dd' 문자열, 금액 · 비율은 number(BigDecimal 직렬화 값). 프론트는 이 값을 다시 계산하지 않는다.
 */

export type AssetClass = 'SECURITIES' | 'DEPOSIT' | 'CASH'

/** 05 §4.4 asset_type — 예금 · 적금 → DEPOSIT 자산군, 입출금 · 현금 · 증권 예수금 → CASH 자산군 */
export type AssetType =
  'CHECKING' | 'DEPOSIT' | 'SAVINGS' | 'SECURITIES' | 'CASH'

export type TrendPeriod = '1M' | '1Y' | 'ALL'

/** AssetChangeLatestRecord — 가장 최근 기록(상태 무관). COMPLETE가 아니면 누락 표시용 */
export interface LatestRecord {
  date: string
  status: SnapshotStatus
}

/** AssetChangeAssetClassChanges — 어느 한쪽 기록의 자산군 금액이 없으면 그 자산군만 null */
export interface AssetClassChanges {
  securities: number | null
  deposit: number | null
  cash: number | null
}

/** AssetChangeComparison — available=false면 나머지 필드는 모두 null */
export interface Comparison {
  available: boolean
  baseDate: string | null
  baseTotal: number | null
  changeAmount: number | null
  /** 퍼센트 값(소수 4자리, 예: 3.3333 = 3.33%). 기준 총자산이 0이면 null */
  changeRate: number | null
  /** 계산할 수 없는 기록이 기간에 있으면 null */
  marketChangeAmount: number | null
  /** 증감액 − 시장가격 영향(원인을 뜻하지 않음). 시장가격 영향이 null이면 null */
  otherChangeAmount: number | null
  assetClassChanges: AssetClassChanges | null
}

/** GET /api/snapshots/changes — AssetChangeResponse */
export interface AssetChangeResponse {
  /** 비교 기준 = 가장 최근 COMPLETE 기록 날짜. 기록이 없으면 null */
  asOfDate: string | null
  totalAsset: number | null
  latestRecord: LatestRecord | null
  day: Comparison
  month: Comparison
  year: Comparison
  /** 첫 COMPLETE 기록 대비 — 전월 · 전년이 available=false일 때 대신 보여준다 */
  sinceFirstRecord: Comparison
}

/** AssetTrendPoint — 그날 저장된 값 그대로 */
export interface TrendPoint {
  date: string
  totalAsset: number | null
  securitiesAmount: number | null
  depositAmount: number | null
  cashAmount: number | null
  /** 직전 COMPLETE 대비. 첫 기록이면 null */
  dayChangeAmount: number | null
  marketChangeAmount: number | null
  otherChangeAmount: number | null
}

/** GET /api/snapshots/trend — AssetTrendResponse */
export interface AssetTrendResponse {
  period: TrendPeriod
  asOfDate: string | null
  /** false = 기간 미달로 연동 이후 첫 기록부터 표시. ALL은 항상 true */
  fullPeriod: boolean
  /** 날짜 오름차순. 첫 점이 시작점 */
  points: TrendPoint[]
  /** 끝점 vs 시작점. 점이 2개 미만이면 available=false */
  summary: Comparison
}

/** PortfolioAssetClassItem */
export interface PortfolioAssetClassItem {
  assetClass: AssetClass
  /** 그 자산군 수집이 전부 실패한 기록이면 null */
  amount: number | null
  /** 총자산 대비 퍼센트 값(소수 2자리). 합계를 100으로 맞추지 않는다 */
  ratio: number | null
}

/** PortfolioHoldingItem — (계좌, 종목) 1개당 1행 */
export interface PortfolioHoldingItem {
  assetId: number
  institutionName: string | null
  accountNumberMasked: string | null
  ticker: string
  stockName: string
  quantity: number | null
  price: number | null
  evalAmount: number | null
  /** 기록 수량 × 현재 평균 매입가. 알 수 없으면 null */
  principal: number | null
  profitLoss: number | null
  /** 퍼센트 값(소수 4자리) */
  profitLossRate: number | null
  /** 총자산 대비 퍼센트 값(소수 2자리) */
  ratio: number | null
  /** 직전 COMPLETE 같은 계좌 · 종목 대비. 새로 산 종목이면 null */
  dayChangeAmount: number | null
}

/** GET /api/snapshots/portfolio — PortfolioResponse (최근 COMPLETE 기록 기준) */
export interface PortfolioResponse {
  asOfDate: string | null
  latestRecord: LatestRecord | null
  /** 모든 비중의 분모 */
  totalAsset: number | null
  /** SECURITIES · DEPOSIT · CASH 순서 */
  assetClasses: PortfolioAssetClassItem[]
  /** 평가금액 내림차순(같으면 종목코드 오름차순) */
  holdings: PortfolioHoldingItem[]
}

export async function fetchAssetChanges(): Promise<AssetChangeResponse> {
  const { data } =
    await apiClient.get<ApiResponse<AssetChangeResponse>>('/snapshots/changes')
  return data.data
}

export async function fetchAssetTrend(
  period: TrendPeriod,
): Promise<AssetTrendResponse> {
  const { data } = await apiClient.get<ApiResponse<AssetTrendResponse>>(
    '/snapshots/trend',
    { params: { period } },
  )
  return data.data
}

export async function fetchPortfolio(): Promise<PortfolioResponse> {
  const { data } = await apiClient.get<ApiResponse<PortfolioResponse>>(
    '/snapshots/portfolio',
  )
  return data.data
}

/* ---------- S-02 자산 기록 (FR-AS-06) ---------- */

/** SnapshotMonthRecord — 그 달 기록 1일. missing=true면 totalAsset · referenceDate는 직전 COMPLETE 값, 증감은 null */
export interface SnapshotMonthRecord {
  date: string
  status: SnapshotStatus
  /** true = 일부 또는 전체 수집 실패로 비교에서 제외된 날 */
  missing: boolean
  totalAsset: number | null
  /** totalAsset의 기록 날짜 — COMPLETE면 그날, 누락이면 직전 COMPLETE 날짜(없으면 null) */
  referenceDate: string | null
  /** 직전 COMPLETE 대비. 누락 날 · 첫 기록은 null */
  dayChangeAmount: number | null
  /** 퍼센트 값(소수 4자리) */
  dayChangeRate: number | null
  marketChangeAmount: number | null
  otherChangeAmount: number | null
  hasEvent: boolean
}

/** GET /api/snapshots?month= — SnapshotMonthResponse */
export interface SnapshotMonthResponse {
  /** 'yyyy-MM'. 달을 생략했는데 기록이 하나도 없으면 null */
  month: string | null
  /** 기록이 있는 첫 달 — 월 이동(◀) 한계. 기록이 없으면 null */
  firstRecordMonth: string | null
  /** 기록이 있는 최근 달 — 월 이동(▶) 한계. 기록이 없으면 null */
  latestRecordMonth: string | null
  /** 그 달 첫 COMPLETE → 마지막 COMPLETE. 끝 날짜 필드는 없다(시작 = baseDate) */
  summary: Comparison
  /** 그 달 COMPLETE 기록 중 전일 대비 증가한 날 수 */
  risingDays: number
  fallingDays: number
  /** 날짜 내림차순, 상태 무관(휴장일은 행이 없다) */
  records: SnapshotMonthRecord[]
}

/** SnapshotDetailAssetClassItem — 기준일이 없거나 어느 한쪽 금액이 없으면 changeAmount는 null */
export interface SnapshotDetailAssetClassItem {
  amount: number | null
  changeAmount: number | null
}

export interface SnapshotDetailAssetClasses {
  securities: SnapshotDetailAssetClassItem
  deposit: SnapshotDetailAssetClassItem
  cash: SnapshotDetailAssetClassItem
}

/** SnapshotDetailHoldingItem — 평가에 쓴 수량 · 단가 · 금액과 전일 대비 */
export interface SnapshotDetailHoldingItem {
  ticker: string
  /** 그 날짜 이하 최신 시세의 종목명. 없으면 종목코드 */
  stockName: string
  quantity: number | null
  price: number | null
  amount: number | null
  /** 직전 COMPLETE 같은 계좌 · 종목 대비. 새로 산 종목이면 null */
  dayChangeAmount: number | null
}

/** SnapshotDetailAssetItem — amount는 증권 계좌 예수금 또는 수기 자산 잔액 */
export interface SnapshotDetailAssetItem {
  assetId: number
  assetType: AssetType
  /** 수기 자산 이름 또는 증권 계좌의 기관명 */
  name: string
  /** 증권 계좌만. 수기 자산은 null */
  accountNumberMasked: string | null
  amount: number | null
  dayChangeAmount: number | null
  holdings: SnapshotDetailHoldingItem[]
}

/** GET /api/snapshots/{date} — SnapshotDetailResponse. missing=true면 증감 · assetClasses는 null, assets는 빈 목록 */
export interface SnapshotDetailResponse {
  date: string
  status: SnapshotStatus
  missing: boolean
  hasEvent: boolean
  /** 비교 기준 기록 날짜(직전 COMPLETE). 첫 기록 · 누락 날은 null */
  baseDate: string | null
  totalAsset: number | null
  referenceDate: string | null
  dayChangeAmount: number | null
  dayChangeRate: number | null
  marketChangeAmount: number | null
  otherChangeAmount: number | null
  assetClasses: SnapshotDetailAssetClasses | null
  assets: SnapshotDetailAssetItem[]
}

/** month 생략 = 백엔드 기본(최근 기록이 있는 달) */
export async function fetchSnapshotMonth(
  month?: string,
): Promise<SnapshotMonthResponse> {
  const { data } = await apiClient.get<ApiResponse<SnapshotMonthResponse>>(
    '/snapshots',
    { params: month ? { month } : undefined },
  )
  return data.data
}

export async function fetchSnapshotDetail(
  date: string,
): Promise<SnapshotDetailResponse> {
  const { data } = await apiClient.get<ApiResponse<SnapshotDetailResponse>>(
    `/snapshots/${date}`,
  )
  return data.data
}
