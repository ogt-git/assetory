import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  fetchDetectionSettings,
  resetDetectionSetting,
  updateDetectionSetting,
  type DetectionSetting,
  type DetectionSettingUpdateRequest,
} from '@/features/detection/api'

export const detectionQueryKeys = {
  settings: ['detection-settings'] as const,
}

/** S-09 규칙별 설정 목록 */
export function useDetectionSettings() {
  return useQuery({
    queryKey: detectionQueryKeys.settings,
    queryFn: fetchDetectionSettings,
  })
}

// 저장 · 되돌리기 응답(그 규칙의 새 상태)으로 목록의 같은 규칙만 바꾼다 — 목록을 다시 받지 않는다
function useReplaceInList() {
  const queryClient = useQueryClient()
  return (updated: DetectionSetting) =>
    queryClient.setQueryData<DetectionSetting[]>(
      detectionQueryKeys.settings,
      (list) =>
        list?.map((item) =>
          item.ruleCode === updated.ruleCode ? updated : item,
        ),
    )
}

export function useUpdateDetectionSetting(ruleCode: string) {
  const replace = useReplaceInList()
  return useMutation({
    mutationFn: (body: DetectionSettingUpdateRequest) =>
      updateDetectionSetting(ruleCode, body),
    onSuccess: replace,
  })
}

export function useResetDetectionSetting(ruleCode: string) {
  const replace = useReplaceInList()
  return useMutation({
    mutationFn: () => resetDetectionSetting(ruleCode),
    onSuccess: replace,
  })
}
