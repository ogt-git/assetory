import { useEffect, useRef, useState } from 'react'
import type {
  SnapshotMonthRecord,
  SnapshotMonthResponse,
} from '@/features/asset/api'
import { OTHER_CHANGE_NOTE } from '@/features/asset/copy'
import NoRecordsNotice from '@/features/asset/NoRecordsNotice'
import RecordDetail from '@/features/asset/RecordDetail'
import { useSnapshotMonth } from '@/features/asset/queries'
import { shiftMonth, useRecordsParams } from '@/features/asset/recordsParams'
import type { SnapshotStatus } from '@/shared/api/types'
import { cn } from '@/shared/lib/cn'
import {
  formatKRW,
  formatRate,
  formatShortDate,
  formatSignedKRW,
  snapshotStatusLabel,
} from '@/shared/lib/format'

function toneClass(value: number | null) {
  if (value == null || value === 0) return 'text-flat'
  return value > 0 ? 'text-rise' : 'text-fall'
}

function recordElementId(date: string) {
  return `record-${date}`
}

/** 'yyyy-MM' -> "2026년 9월" */
function monthLabel(month: string) {
  return `${month.slice(0, 4)}년 ${Number(month.slice(5, 7))}월`
}

/**
 * S-02 자산 기록 — 월 단위 날짜별 기록 (03 §3, FR-AS-06). 값은 /snapshots?month= 그대로 표시한다.
 * 휴장일은 기록이 없어 행도 없다 — 프론트에서 날짜를 채우지 않는다.
 */
export default function RecordsMonth() {
  const { month, focusDate, goToMonth } = useRecordsParams()
  const monthQuery = useSnapshotMonth(month)
  // 펼친 날짜 — 여러 행을 함께 펼칠 수 있다. 상세는 펼친 행만 조회한다.
  // ?date=로 들어오면(대시보드 추이 차트 점 클릭) 그 날짜를 펼친 채로 시작한다 — 그 날짜 행이 없으면(휴장일 등) 아무 일도 없다
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(
    () => new Set(focusDate ? [focusDate] : []),
  )
  // 진입한 날짜 행으로 한 번만 스크롤한다(이전 달 목록이 잠깐 보이는 동안에는 기다린다)
  const scrolledRef = useRef(false)
  const focusInList =
    focusDate != null &&
    !monthQuery.isPlaceholderData &&
    monthQuery.data?.records.some((r) => r.date === focusDate) === true
  useEffect(() => {
    if (!focusInList || !focusDate || scrolledRef.current) return
    scrolledRef.current = true
    document
      .getElementById(recordElementId(focusDate))
      ?.scrollIntoView({ block: 'center' })
  }, [focusInList, focusDate])
  const toggle = (date: string) =>
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(date)) next.delete(date)
      else next.add(date)
      return next
    })

  if (monthQuery.isPending) {
    return <p className="text-flat text-sm">불러오는 중…</p>
  }
  if (monthQuery.isError) {
    return (
      <div className="flex items-center gap-2 text-sm">
        <p className="text-rise">자산 기록을 불러오지 못했어요.</p>
        <button
          type="button"
          className="rounded border px-2 py-0.5"
          onClick={() => void monthQuery.refetch()}
        >
          다시 시도
        </button>
      </div>
    )
  }

  const data = monthQuery.data
  // 기록이 하나도 없으면 월 이동 대신 연동 안내(대시보드와 같은 안내)
  if (!data.month || !data.firstRecordMonth || !data.latestRecordMonth) {
    return <NoRecordsNotice />
  }

  return (
    // 월을 옮기는 동안에는 이전 달을 흐리게 유지한다
    <div
      className={cn(
        'flex flex-col gap-4',
        monthQuery.isPlaceholderData && 'opacity-50',
      )}
    >
      <MonthHeader
        month={data.month}
        first={data.firstRecordMonth}
        latest={data.latestRecordMonth}
        onMove={goToMonth}
      />
      {data.records.length === 0 ? (
        <p className="text-flat text-sm">이 달에는 기록이 없어요.</p>
      ) : (
        <>
          <MonthSummary data={data} />
          <ul className="divide-y rounded border">
            {data.records.map((record) => (
              <RecordRow
                key={record.date}
                record={record}
                open={expanded.has(record.date)}
                onToggle={() => toggle(record.date)}
              />
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

function MonthHeader({
  month,
  first,
  latest,
  onMove,
}: {
  month: string
  first: string
  latest: string
  onMove: (month: string) => void
}) {
  // 'yyyy-MM' 문자열은 사전순 = 시간순
  const canPrev = month > first
  const canNext = month < latest

  return (
    <div className="flex items-center justify-between">
      <button
        type="button"
        aria-label="이전 달"
        className="rounded border px-3 py-1 disabled:opacity-30"
        disabled={!canPrev}
        onClick={() => onMove(shiftMonth(month, -1))}
      >
        ◀
      </button>
      <h2 className="text-lg font-semibold" aria-live="polite">
        {monthLabel(month)}
      </h2>
      <button
        type="button"
        aria-label="다음 달"
        className="rounded border px-3 py-1 disabled:opacity-30"
        disabled={!canNext}
        onClick={() => onMove(shiftMonth(month, 1))}
      >
        ▶
      </button>
    </div>
  )
}

/** 이번 달 요약 — 그 달 첫 COMPLETE → 마지막 COMPLETE (월간 결산과 같은 기준, 01 §5). 항상 펼쳐 둔다 */
function MonthSummary({ data }: { data: SnapshotMonthResponse }) {
  const [noteOpen, setNoteOpen] = useState(false)
  const { summary } = data
  // 끝 날짜 필드가 응답에 없다 → 목록(날짜 내림차순)에서 가장 최근 COMPLETE 날짜
  const endDate = data.records.find((r) => r.status === 'COMPLETE')?.date

  if (!summary.available) {
    return (
      <section aria-label="이번 달 요약" className="rounded border p-3 text-sm">
        <p className="text-flat">
          이 달은 비교할 기록이 2일 이상 쌓이면 보여드려요.
        </p>
      </section>
    )
  }

  return (
    <section
      aria-label="이번 달 요약"
      className="flex flex-col gap-2 rounded border p-3"
    >
      <p className="text-flat text-xs">
        이번 달 {formatShortDate(summary.baseDate)} → {formatShortDate(endDate)}
      </p>
      <p
        className={cn(
          'text-lg font-semibold tabular-nums',
          toneClass(summary.changeAmount),
        )}
      >
        {formatSignedKRW(summary.changeAmount)}{' '}
        <span className="text-sm">({formatRate(summary.changeRate)})</span>
      </p>
      <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 text-sm">
        <dt>시장가격 영향</dt>
        <dd
          className={cn(
            'text-right tabular-nums',
            toneClass(summary.marketChangeAmount),
          )}
        >
          {formatSignedKRW(summary.marketChangeAmount)}
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
            toneClass(summary.otherChangeAmount),
          )}
        >
          {formatSignedKRW(summary.otherChangeAmount)}
        </dd>
      </dl>
      {noteOpen && <p className="text-flat text-xs">{OTHER_CHANGE_NOTE}</p>}
      <p className="text-sm">
        <span className="text-rise">상승 {data.risingDays}일</span>
        <span className="text-flat"> · </span>
        <span className="text-fall">하락 {data.fallingDays}일</span>
      </p>
    </section>
  )
}

interface RowProps {
  record: SnapshotMonthRecord
  open: boolean
  onToggle: () => void
}

function RecordRow({ record, open, onToggle }: RowProps) {
  if (record.missing) {
    return <MissingRow record={record} open={open} onToggle={onToggle} />
  }
  const change = record.dayChangeAmount

  return (
    <li id={recordElementId(record.date)}>
      <button
        type="button"
        aria-expanded={open}
        className="grid w-full grid-cols-[auto_1fr_auto] items-center gap-x-3 px-3 py-2 text-left text-sm"
        onClick={onToggle}
      >
        <span className="tabular-nums">{formatShortDate(record.date)}</span>
        <span className="text-right font-medium tabular-nums">
          {formatKRW(record.totalAsset)}
        </span>
        <span className={cn('w-28 text-right tabular-nums', toneClass(change))}>
          {formatSignedKRW(change)}
          {change != null && change !== 0 && (
            <span aria-hidden="true">{change > 0 ? ' ▲' : ' ▼'}</span>
          )}
          {record.hasEvent && (
            // 그날 이벤트가 있다는 표시만 — 원인 분석(S-04)이 생기면 연결한다
            <span role="img" aria-label="이벤트 있음" className="ml-1">
              🔔
            </span>
          )}
        </span>
      </button>
      {/* 펼쳤을 때만 마운트 → 그때만 상세를 조회한다 */}
      {open && <RecordDetail date={record.date} />}
    </li>
  )
}

// 누락 날 펼침 안내 — 상태별로 자연스러운 문장
const MISSING_REASON: Record<SnapshotStatus, string> = {
  COMPLETE: '',
  PARTIAL: '일부 누락되어',
  FAILED: '수집에 실패해',
}

/** 누락 날 — 직전 COMPLETE 금액 + 기준 날짜 + 상태. 부분 금액 · 증감은 보여주지 않는다 (01 §14 #19) */
function MissingRow({ record, open, onToggle }: RowProps) {
  const status = snapshotStatusLabel(record.status)

  return (
    <li
      id={recordElementId(record.date)}
      className="bg-partial/10 text-partial"
    >
      <button
        type="button"
        aria-expanded={open}
        className="flex w-full items-center justify-between gap-3 px-3 py-2 text-left text-sm"
        onClick={onToggle}
      >
        <span className="tabular-nums">
          {formatShortDate(record.date)} · {status} —{' '}
          {record.referenceDate
            ? `${formatShortDate(record.referenceDate, record.date)} 기준 ${formatKRW(record.totalAsset)}`
            : '비교할 이전 기록이 없어요'}
        </span>
        {/* 기준 기록이 없으면 금액도 없다 — 0으로 채우지 않는다 */}
        {!record.referenceDate && <span className="tabular-nums">—</span>}
      </button>
      {/* 누락 날은 상세 API를 부르지 않는다 — 보여줄 부분 금액이 없다 */}
      {open && (
        <p className="px-3 pb-2 text-xs">
          {MISSING_REASON[record.status]} 비교에서 제외된 날이에요.
          {record.referenceDate &&
            ` ${formatShortDate(record.referenceDate, record.date)} 기록 기준 금액을 보여드려요.`}
        </p>
      )}
    </li>
  )
}
