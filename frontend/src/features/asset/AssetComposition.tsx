import type { AssetClass, PortfolioResponse } from '@/features/asset/api'
import { usePortfolio } from '@/features/asset/queries'
import { formatKRW, formatRate, formatShortDate } from '@/shared/lib/format'

const CLASS_LABELS: Record<AssetClass, string> = {
  SECURITIES: '증권',
  DEPOSIT: '예적금',
  CASH: '현금',
}

// 자산군 구분색 — rise · fall 토큰은 상승 · 하락 뜻이라 쓰지 않고 무채색 단계로 나눈다
const CLASS_COLORS: Record<AssetClass, string> = {
  SECURITIES: 'bg-neutral-800',
  DEPOSIT: 'bg-neutral-500',
  CASH: 'bg-neutral-300',
}

/** ⑤ 자산 구성 (S-01, FR-AS-04) — 금액 · 비중은 /snapshots/portfolio 그대로. 합계를 100으로 맞추지 않는다 */
export default function AssetComposition() {
  const portfolio = usePortfolio()

  return (
    <section
      aria-labelledby="asset-composition-title"
      className="flex flex-col gap-3"
    >
      <h2 id="asset-composition-title" className="text-flat text-sm">
        자산 구성
      </h2>
      {portfolio.isPending && <p className="text-flat text-sm">불러오는 중…</p>}
      {portfolio.isError && (
        <div className="flex items-center gap-2 text-sm">
          <p className="text-rise">자산 구성을 불러오지 못했어요.</p>
          <button
            type="button"
            className="rounded border px-2 py-0.5"
            onClick={() => void portfolio.refetch()}
          >
            다시 시도
          </button>
        </div>
      )}
      {portfolio.data && <CompositionBody data={portfolio.data} />}
    </section>
  )
}

function CompositionBody({ data }: { data: PortfolioResponse }) {
  if (!data.asOfDate || data.assetClasses.length === 0) {
    return <p className="text-flat text-sm">아직 자산 기록이 없어요.</p>
  }

  return (
    <div className="flex flex-col gap-2">
      <p className="text-flat text-xs">
        {formatShortDate(data.asOfDate)} 기록 기준
      </p>
      {/* 막대 폭 = 백엔드 비중 그대로(비중이 없으면 그리지 않음). 반올림 합이 100이 아니어도 보정하지 않는다 */}
      <div
        className="flex h-3 w-full overflow-hidden rounded-full bg-neutral-100"
        aria-hidden="true"
      >
        {data.assetClasses.map((c) =>
          c.ratio == null || c.ratio <= 0 ? null : (
            <div
              key={c.assetClass}
              className={CLASS_COLORS[c.assetClass]}
              style={{ width: `${Math.min(c.ratio, 100)}%` }}
            />
          ),
        )}
      </div>
      <ul className="flex flex-col gap-1 text-sm">
        {data.assetClasses.map((c) => (
          <li key={c.assetClass} className="flex items-center gap-2">
            <span
              className={`size-2.5 rounded-full ${CLASS_COLORS[c.assetClass]}`}
              aria-hidden="true"
            />
            <span className="flex-1">{CLASS_LABELS[c.assetClass]}</span>
            <span className="tabular-nums">{formatKRW(c.amount)}</span>
            <span className="text-flat w-16 text-right tabular-nums">
              {formatRate(c.ratio, { signed: false })}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}
