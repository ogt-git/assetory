import { apiClient } from '@/shared/api/client'
import type { ApiResponse } from '@/shared/api/types'

/** 감지 규칙 유형 (05 §8.1 detection_rules.trigger_type) */
export type TriggerType =
  | 'STOCK_CHANGE'
  | 'ASSET_CHANGE'
  | 'DEPOSIT_MATURITY'
  | 'GOAL_PROGRESS'
  | 'BRIEFING'
  | 'MONTHLY_REPORT'

/** 제외 종목을 받는 유일한 규칙 (A2 결정 3) */
export const STOCK_PRICE_CHANGE = 'STOCK_PRICE_CHANGE'

/** DetectionSettingResponse — 규칙 정보 + 내 설정 + 실제 적용값. 숫자는 기준값 단위(unit) 그대로 */
export interface DetectionSetting {
  ruleCode: string
  name: string
  triggerType: TriggerType
  /** '%' · '일'. 기준값이 없는 규칙은 null */
  unit: string | null
  defaultThreshold: number | null
  /** 허용 범위 — 조정할 수 없는 규칙은 null */
  minValue: number | null
  maxValue: number | null
  /** 기준값을 바꿀 수 있는지 (수신 켜기 · 끄기는 모든 규칙에서 가능) */
  userConfigurable: boolean
  /** 내 기준값 — null이면 기본값 사용 */
  thresholdValue: number | null
  /** 실제 적용 기준값 = 내 값 ?? 기본값 */
  appliedThreshold: number | null
  enabled: boolean
  excludedTickers: string[]
  /** 내 설정이 있는지 (false = 모두 기본값) */
  customized: boolean
}

/** PUT 본문 — 전체 교체라 세 값을 항상 모두 보낸다 */
export interface DetectionSettingUpdateRequest {
  enabled: boolean
  /** null이면 기본값 사용 */
  thresholdValue: number | null
  /** 급등락 규칙만. 빈 목록 = 제외 없음 */
  excludedTickers: string[]
}

/** 저장 거부(400 THRESHOLD_OUT_OF_RANGE)일 때 오류 응답 data — 허용 범위 */
export interface ThresholdRange {
  minValue: number
  maxValue: number
  unit: string | null
}

export async function fetchDetectionSettings(): Promise<DetectionSetting[]> {
  const { data } = await apiClient.get<ApiResponse<DetectionSetting[]>>(
    '/detection-settings',
  )
  return data.data
}

export async function updateDetectionSetting(
  ruleCode: string,
  body: DetectionSettingUpdateRequest,
): Promise<DetectionSetting> {
  const { data } = await apiClient.put<ApiResponse<DetectionSetting>>(
    `/detection-settings/${ruleCode}`,
    body,
  )
  return data.data
}

/** 기본값으로 되돌리기 — 내 설정 삭제, 기본값 응답 */
export async function resetDetectionSetting(
  ruleCode: string,
): Promise<DetectionSetting> {
  const { data } = await apiClient.delete<ApiResponse<DetectionSetting>>(
    `/detection-settings/${ruleCode}`,
  )
  return data.data
}
