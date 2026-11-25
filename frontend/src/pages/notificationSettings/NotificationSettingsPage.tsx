import DetectionRuleCard from '@/features/detection/DetectionRuleCard'
import { useDetectionSettings } from '@/features/detection/queries'

/**
 * S-09 알림 설정 (03 §1, UC-07) — 규칙별 기준값 · 수신 · 제외 종목. 바꾼 값은 다음 감지부터 적용된다.
 * 채널 · 텔레그램 · 방해 금지는 알림 기능(12주차)과 함께 추가한다.
 */
export default function NotificationSettingsPage() {
  const settings = useDetectionSettings()

  return (
    <div className="mx-auto flex max-w-xl flex-col gap-4 p-4">
      <h1 className="text-xl font-bold">알림 설정</h1>
      <p className="text-flat text-xs">바꾼 기준은 다음 감지부터 적용돼요.</p>

      {settings.isPending && <p className="text-flat">불러오는 중…</p>}
      {settings.isError && (
        <div className="flex items-center gap-2 text-sm">
          <p className="text-rise">설정을 불러오지 못했어요.</p>
          <button
            type="button"
            className="rounded border bg-white px-2 py-0.5"
            onClick={() => void settings.refetch()}
          >
            다시 시도
          </button>
        </div>
      )}
      {settings.data && (
        <ul className="flex flex-col gap-3">
          {settings.data.map((setting) => (
            // 서버 값이 바뀌면(저장 · 되돌리기) 카드의 입력 상태를 새 값으로 다시 시작한다
            <DetectionRuleCard
              key={`${setting.ruleCode}:${setting.thresholdValue}:${setting.enabled}:${setting.excludedTickers.join(',')}`}
              setting={setting}
            />
          ))}
        </ul>
      )}
    </div>
  )
}
