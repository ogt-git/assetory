/** 백엔드 공통 응답 envelope (global/response/ApiResponse) */
export interface ApiResponse<T> {
  success: boolean
  data: T
  message: string
  errorCode: string | null
  timestamp: string
}

/** 일별 기록 수집 상태 (05 asset_snapshots.status) — 기록 · 결산 화면이 같이 쓴다 */
export type SnapshotStatus = 'COMPLETE' | 'PARTIAL' | 'FAILED'
