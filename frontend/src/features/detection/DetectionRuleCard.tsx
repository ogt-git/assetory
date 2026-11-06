import axios from 'axios'
import { useState, type FormEvent } from 'react'
import { errorMessage } from '@/features/auth/session'
import {
  STOCK_PRICE_CHANGE,
  type DetectionSetting,
  type ThresholdRange,
} from '@/features/detection/api'
import {
  useResetDetectionSetting,
  useUpdateDetectionSetting,
} from '@/features/detection/queries'
import { formatThreshold } from '@/shared/lib/format'

/** 저장 실패 문구 — 범위 밖이면 허용 범위로 안내, 그 외는 서버 문구(ErrorCode) 그대로 */
function saveErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const range = (error.response?.data as { data?: ThresholdRange | null })
      ?.data
    if (range && range.minValue != null && range.maxValue != null) {
      return `${formatThreshold(range.minValue)}~${formatThreshold(range.maxValue, range.unit)} 사이로 입력해 주세요`
    }
  }
  return errorMessage(error, '저장하지 못했어요. 잠시 후 다시 시도해 주세요.')
}

/**
 * S-09 규칙 1개 카드 (UC-07, FR-DT-02) — 기준값(조정 가능한 규칙만) · 수신 · 제외 종목(급등락만), 카드 단위 저장.
 * 저장은 전체 교체(PUT)라 세 값을 항상 모두 보낸다. 기준값 칸을 비우면 기본값을 쓴다.
 * 서버 값이 바뀌면 부모가 key를 바꿔 이 카드를 새 값으로 다시 그린다.
 */
export default function DetectionRuleCard({
  setting,
}: {
  setting: DetectionSetting
}) {
  const [threshold, setThreshold] = useState(
    setting.thresholdValue == null ? '' : String(setting.thresholdValue),
  )
  const [enabled, setEnabled] = useState(setting.enabled)
  const [excluded, setExcluded] = useState<string[]>(setting.excludedTickers)
  const [tickerInput, setTickerInput] = useState('')
  const update = useUpdateDetectionSetting(setting.ruleCode)
  const reset = useResetDetectionSetting(setting.ruleCode)
  const supportsExclusion = setting.ruleCode === STOCK_PRICE_CHANGE
  const busy = update.isPending || reset.isPending

  const onSave = (event: FormEvent) => {
    event.preventDefault()
    const trimmed = threshold.trim()
    update.mutate({
      enabled,
      thresholdValue:
        setting.userConfigurable && trimmed !== '' ? Number(trimmed) : null,
      excludedTickers: supportsExclusion ? excluded : [],
    })
  }

  const addTicker = () => {
    // 한투 종목코드는 대문자 — 소문자로 입력해도 대문자로 넣는다(형식 검사는 서버)
    const ticker = tickerInput.trim().toUpperCase()
    if (ticker && !excluded.includes(ticker)) {
      setExcluded([...excluded, ticker])
    }
    setTickerInput('')
  }

  return (
    <li className="rounded border bg-white">
      <form className="flex flex-col gap-3 p-3" onSubmit={onSave}>
        <div className="flex items-baseline justify-between gap-2">
          <h2 className="font-medium">{setting.name}</h2>
          <span className="text-flat shrink-0 text-xs">
            {setting.customized ? '내 설정' : '기본값'}
          </span>
        </div>

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
          />
          알림 받기
        </label>

        {setting.userConfigurable && (
          <div className="flex flex-col gap-1 text-sm">
            <label className="flex flex-wrap items-center gap-2">
              <span>기준값</span>
              <input
                type="number"
                inputMode="decimal"
                step="any"
                className="w-24 rounded border px-2 py-1 text-right tabular-nums"
                placeholder={formatThreshold(setting.defaultThreshold)}
                value={threshold}
                onChange={(e) => setThreshold(e.target.value)}
              />
              <span>{setting.unit}</span>
            </label>
            <p className="text-flat text-xs">
              기본 {formatThreshold(setting.defaultThreshold, setting.unit)} ·{' '}
              {formatThreshold(setting.minValue)}~
              {formatThreshold(setting.maxValue, setting.unit)} · 지금 적용{' '}
              {formatThreshold(setting.appliedThreshold, setting.unit)}
            </p>
          </div>
        )}

        {supportsExclusion && (
          <div className="flex flex-col gap-2 text-sm">
            <span>알림에서 뺄 종목</span>
            {excluded.length > 0 && (
              <ul className="flex flex-wrap gap-1">
                {excluded.map((ticker) => (
                  <li
                    key={ticker}
                    className="flex items-center gap-1 rounded border px-2 py-0.5 tabular-nums"
                  >
                    {ticker}
                    <button
                      type="button"
                      aria-label={`${ticker} 제외 해제`}
                      className="text-flat"
                      onClick={() =>
                        setExcluded(excluded.filter((t) => t !== ticker))
                      }
                    >
                      ✕
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <div className="flex gap-2">
              <input
                className="w-28 rounded border px-2 py-1 tabular-nums"
                placeholder="종목코드 6자리"
                maxLength={6}
                value={tickerInput}
                onChange={(e) => setTickerInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault()
                    addTicker()
                  }
                }}
              />
              <button
                type="button"
                className="rounded border px-2 py-1"
                onClick={addTicker}
              >
                추가
              </button>
            </div>
          </div>
        )}

        {update.isError && (
          <p className="text-rise text-xs" role="alert">
            {saveErrorMessage(update.error)}
          </p>
        )}
        {reset.isError && (
          <p className="text-rise text-xs" role="alert">
            {errorMessage(
              reset.error,
              '되돌리지 못했어요. 잠시 후 다시 시도해 주세요.',
            )}
          </p>
        )}

        <div className="flex justify-end gap-2 text-sm">
          <button
            type="button"
            className="rounded border px-3 py-1 disabled:opacity-50"
            disabled={busy || !setting.customized}
            onClick={() => reset.mutate()}
          >
            기본값으로
          </button>
          <button
            type="submit"
            className="rounded border bg-neutral-900 px-3 py-1 text-white disabled:opacity-50"
            disabled={busy}
          >
            {update.isPending ? '저장 중…' : '저장'}
          </button>
        </div>
      </form>
    </li>
  )
}
