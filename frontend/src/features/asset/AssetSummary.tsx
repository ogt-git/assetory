import { useState } from 'react'
import type { AssetChangeResponse, Comparison } from '@/features/asset/api'
import { OTHER_CHANGE_NOTE } from '@/features/asset/copy'
import { useAssetChanges } from '@/features/asset/queries'
import { cn } from '@/shared/lib/cn'
import {
  formatKRW,
  formatRate,
  formatShortDate,
  formatSignedKRW,
  snapshotStatusLabel,
} from '@/shared/lib/format'

type Tab = 'day' | 'month' | 'year'

const TABS: { key: Tab; label: string }[] = [
  { key: 'day', label: '전일' },
  { key: 'month', label: '전월' },
  { key: 'year', label: '전년' },
]

const NO_COMPARISON = '다음 거래일 기록부터 비교할 수 있어요'

/** 증감 색 — 0 · null은 변동 없음 색 */
function toneClass(value: number | null) {
  if (value == null || value === 0) return 'text-flat'
  return value > 0 ? 'text-rise' : 'text-fall'
}

/**
 * 탭의 비교 — 전월 · 전년이 기간 미달(available=false)이면 연동 이후 누적으로 대신 보여준다(FR-AN-06).
 * 대신 보여줄 것도 없으면 null.
 */
function pickComparison(data: AssetChangeResponse, tab: Tab) {
  const own = data[tab]
  if (own.available) return { comparison: own, sinceFirst: false }
  if (tab !== 'day' && data.sinceFirstRecord.available) {
    return { comparison: data.sinceFirstRecord, sinceFirst: true }
  }
  return null
}

/** ② 총자산 요약 (S-01) — 값은 /snapshots/changes 그대로 표시한다(재계산 없음) */
export default function AssetSummary() {
  const changes = useAssetChanges()
  const [tab, setTab] = useState<Tab>('day')

  return (
    <section
      aria-labelledby="asset-summary-title"
      className="flex flex-col gap-3"
    >
      <h2 id="asset-summary-title" className="text-flat text-sm">
        총자산
      </h2>
      {changes.isPending && <p className="text-flat text-sm">불러오는 중…</p>}
      {changes.isError && (
        <div className="flex items-center gap-2 text-sm">
          <p className="text-rise">총자산 정보를 불러오지 못했어요.</p>
          <button
            type="button"
            className="rounded border px-2 py-0.5"
            onClick={() => void changes.refetch()}
          >
            다시 시도
          </button>
        </div>
      )}
      {changes.data && (
        <SummaryBody data={changes.data} tab={tab} onTabChange={setTab} />
      )}
    </section>
  )
}

function SummaryBody({
  data,
  tab,
  onTabChange,
}: {
  data: AssetChangeResponse
  tab: Tab
  onTabChange: (tab: Tab) => void
}) {
  if (!data.asOfDate) {
    return <p className="text-flat text-sm">아직 자산 기록이 없어요.</p>
  }
  const latest = data.latestRecord
  const picked = pickComparison(data, tab)

  return (
    <>
      {/* 최근 기록이 PARTIAL · FAILED면 최근 COMPLETE 값을 보여주고 그 사실을 알린다 — 부분 금액은 보여주지 않는다(01 §14 #19) */}
      {latest && latest.status !== 'COMPLETE' && (
        <p
          role="status"
          className="border-partial/40 bg-partial/10 text-partial rounded border px-3 py-2 text-sm"
        >
          {formatShortDate(latest.date)} 기록은{' '}
          {snapshotStatusLabel(latest.status)}되어{' '}
          {formatShortDate(data.asOfDate)} 기록 기준으로 보여드려요
        </p>
      )}

      <div>
        <p className="text-3xl font-bold tabular-nums">
          {formatKRW(data.totalAsset)}
        </p>
        <p className="text-flat text-xs">
          {formatShortDate(data.asOfDate)} 기록 기준
        </p>
      </div>

      <div role="tablist" aria-label="비교 기간" className="flex gap-1">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            aria-selected={tab === t.key}
            className={cn(
              'rounded-full border px-3 py-1 text-sm',
              tab === t.key && 'bg-black text-white',
            )}
            onClick={() => onTabChange(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div role="tabpanel" className="rounded border p-3">
        {picked ? (
          <ComparisonView
            comparison={picked.comparison}
            sinceFirst={picked.sinceFirst}
            asOfDate={data.asOfDate}
          />
        ) : (
          <p className="text-flat text-sm">{NO_COMPARISON}</p>
        )}
      </div>
    </>
  )
}

function ComparisonView({
  comparison,
  sinceFirst,
  asOfDate,
}: {
  comparison: Comparison
  sinceFirst: boolean
  asOfDate: string
}) {
  const [noteOpen, setNoteOpen] = useState(false)
  // 해가 다르면 연도를 붙인다(전년 대비 등)
  const base = formatShortDate(comparison.baseDate, asOfDate)

  return (
    <div className="flex flex-col gap-2">
      <p className="text-flat text-xs">
        {sinceFirst ? `연동 이후 누적 (첫 기록 ${base} 대비)` : `${base} 대비`}
      </p>
      <p
        className={cn(
          'text-xl font-semibold tabular-nums',
          toneClass(comparison.changeAmount),
        )}
      >
        {formatSignedKRW(comparison.changeAmount)}{' '}
        <span className="text-base">({formatRate(comparison.changeRate)})</span>
      </p>
      <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 text-sm">
        <dt>시장가격 영향</dt>
        <dd
          className={cn(
            'text-right tabular-nums',
            toneClass(comparison.marketChangeAmount),
          )}
        >
          {formatSignedKRW(comparison.marketChangeAmount)}
        </dd>
        <dt className="flex items-center gap-1">
          기타 자산 변화
          <button
            type="button"
            aria-label="기타 자산 변화 설명"
            aria-expanded={noteOpen}
            className="text-flat size-5 rounded-full border text-xs leading-none"
            onClick={() => setNoteOpen((open) => !open)}
          >
            i
          </button>
        </dt>
        <dd
          className={cn(
            'text-right tabular-nums',
            toneClass(comparison.otherChangeAmount),
          )}
        >
          {formatSignedKRW(comparison.otherChangeAmount)}
        </dd>
      </dl>
      {noteOpen && <p className="text-flat text-xs">{OTHER_CHANGE_NOTE}</p>}
    </div>
  )
}
