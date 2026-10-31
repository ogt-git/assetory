import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { paths } from '@/app/paths'
import type { AssetTrendResponse, TrendPeriod } from '@/features/asset/api'
import { useAssetTrend } from '@/features/asset/queries'
import { cn } from '@/shared/lib/cn'
import {
  formatKRW,
  formatRate,
  formatShortDate,
  formatSignedKRW,
} from '@/shared/lib/format'

const PERIODS: { key: TrendPeriod; label: string }[] = [
  { key: '1M', label: '1개월' },
  { key: '1Y', label: '1년' },
  { key: 'ALL', label: '전체' },
]

function toneClass(value: number | null) {
  if (value == null || value === 0) return 'text-flat'
  return value > 0 ? 'text-rise' : 'text-fall'
}

function lineColor(value: number | null) {
  if (value == null || value === 0) return 'var(--color-flat)'
  return value > 0 ? 'var(--color-rise)' : 'var(--color-fall)'
}

/** 축 눈금 'yyyy-MM-dd' -> "10/29" */
function tickDate(date: string) {
  return `${Number(date.slice(5, 7))}/${Number(date.slice(8, 10))}`
}

/** 축 눈금 금액 — 만 원 단위로 줄인다. 예: 27,375,910 -> "2,738만" */
function tickAmount(value: number) {
  return `${Math.round(value / 10_000).toLocaleString('ko-KR')}만`
}

/** ③ 총자산 추이 (S-01, FR-UI-02) — 값은 /snapshots/trend 그대로. 점을 누르면 그날 자산 기록(S-02)으로 간다 */
export default function AssetTrendChart() {
  const [period, setPeriod] = useState<TrendPeriod>('1Y')
  const trend = useAssetTrend(period)

  return (
    <section
      aria-labelledby="asset-trend-title"
      className="flex flex-col gap-3"
    >
      <div className="flex items-center justify-between">
        <h2 id="asset-trend-title" className="text-flat text-sm">
          총자산 추이
        </h2>
        <div role="tablist" aria-label="추이 기간" className="flex gap-1">
          {PERIODS.map((p) => (
            <button
              key={p.key}
              type="button"
              role="tab"
              aria-selected={period === p.key}
              className={cn(
                'rounded-full border px-3 py-1 text-sm',
                period === p.key && 'bg-black text-white',
              )}
              onClick={() => setPeriod(p.key)}
            >
              {p.label}
            </button>
          ))}
        </div>
      </div>

      {trend.isPending && <p className="text-flat text-sm">불러오는 중…</p>}
      {trend.isError && (
        <div className="flex items-center gap-2 text-sm">
          <p className="text-rise">추이를 불러오지 못했어요.</p>
          <button
            type="button"
            className="rounded border px-2 py-0.5"
            onClick={() => void trend.refetch()}
          >
            다시 시도
          </button>
        </div>
      )}
      {trend.data && (
        // 기간을 바꾸는 동안에는 이전 기간 차트를 흐리게 유지한다
        <div className={cn(trend.isPlaceholderData && 'opacity-50')}>
          <TrendBody data={trend.data} />
        </div>
      )}
    </section>
  )
}

function TrendBody({ data }: { data: AssetTrendResponse }) {
  const navigate = useNavigate()
  const { points, summary } = data

  if (points.length <= 1) {
    return (
      <p className="text-flat text-sm">
        기록이 이틀 이상 쌓이면 추이를 보여드려요
      </p>
    )
  }

  // recharts는 활성 점 위치를 숫자 또는 문자열 인덱스로 준다(없으면 null · undefined)
  const onPointClick = (index: number | string | null | undefined) => {
    const point = index == null ? undefined : points[Number(index)]
    if (point) navigate(paths.records(point.date))
  }

  return (
    <div className="flex flex-col gap-2">
      {summary.available && (
        <p className="text-sm">
          <span className="text-flat">
            {formatShortDate(summary.baseDate, data.asOfDate)} 대비{' '}
          </span>
          <span
            className={cn(
              'font-semibold tabular-nums',
              toneClass(summary.changeAmount),
            )}
          >
            {formatSignedKRW(summary.changeAmount)} (
            {formatRate(summary.changeRate)})
          </span>
        </p>
      )}
      {!data.fullPeriod && (
        <p className="text-flat text-xs">연동 이후 기록부터 보여드려요</p>
      )}

      <div
        className="h-56 w-full cursor-pointer"
        role="img"
        aria-label={`총자산 추이 ${formatShortDate(points[0].date, data.asOfDate)}부터 ${formatShortDate(points[points.length - 1].date)}까지`}
      >
        <ResponsiveContainer width="100%" height="100%">
          <LineChart
            data={points}
            margin={{ top: 8, right: 8, bottom: 0, left: 0 }}
            onClick={(state) => onPointClick(state.activeIndex)}
          >
            <XAxis
              dataKey="date"
              tickFormatter={tickDate}
              minTickGap={24}
              tick={{ fontSize: 11 }}
              tickLine={false}
            />
            <YAxis
              domain={['auto', 'auto']}
              tickFormatter={tickAmount}
              width={56}
              tick={{ fontSize: 11 }}
              tickLine={false}
              axisLine={false}
            />
            <Tooltip
              content={({ active, activeIndex }) => {
                const point =
                  active && activeIndex != null
                    ? points[Number(activeIndex)]
                    : undefined
                if (!point) return null
                return (
                  <div className="rounded border bg-white px-2 py-1 text-xs shadow">
                    <p>{formatShortDate(point.date, data.asOfDate)}</p>
                    <p className="font-semibold tabular-nums">
                      {formatKRW(point.totalAsset)}
                    </p>
                    <p
                      className={cn(
                        'tabular-nums',
                        toneClass(point.dayChangeAmount),
                      )}
                    >
                      그날 {formatSignedKRW(point.dayChangeAmount)}
                    </p>
                  </div>
                )
              }}
            />
            <Line
              type="monotone"
              dataKey="totalAsset"
              stroke={lineColor(summary.changeAmount)}
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 5 }}
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
      <p className="text-flat text-xs">
        점을 누르면 그날 자산 기록으로 이동해요
      </p>
    </div>
  )
}
