import { Link } from 'react-router-dom'
import { paths } from '@/app/paths'
import type {
  PortfolioHoldingItem,
  PortfolioResponse,
} from '@/features/asset/api'
import { usePortfolio } from '@/features/asset/queries'
import { cn } from '@/shared/lib/cn'
import {
  formatKRW,
  formatRate,
  formatShortDate,
  formatSignedKRW,
} from '@/shared/lib/format'

function toneClass(value: number | null) {
  if (value == null || value === 0) return 'text-flat'
  return value > 0 ? 'text-rise' : 'text-fall'
}

/**
 * ⑥ 보유 종목 (S-01, FR-AS-03) — 최근 COMPLETE 기록 기준, /snapshots/portfolio 순서 그대로(평가금액 내림차순).
 * 같은 종목이 두 계좌에 있으면 두 행. 종목 상세(S-05)가 생기면 행 클릭을 연결한다.
 */
export default function HoldingList() {
  const portfolio = usePortfolio()

  return (
    <section
      aria-labelledby="holding-list-title"
      className="flex flex-col gap-3"
    >
      <h2 id="holding-list-title" className="text-flat text-sm">
        보유 종목
      </h2>
      {portfolio.isPending && <p className="text-flat text-sm">불러오는 중…</p>}
      {portfolio.isError && (
        <div className="flex items-center gap-2 text-sm">
          <p className="text-rise">보유 종목을 불러오지 못했어요.</p>
          <button
            type="button"
            className="rounded border px-2 py-0.5"
            onClick={() => void portfolio.refetch()}
          >
            다시 시도
          </button>
        </div>
      )}
      {portfolio.data && <HoldingBody data={portfolio.data} />}
    </section>
  )
}

function HoldingBody({ data }: { data: PortfolioResponse }) {
  if (data.holdings.length === 0) {
    return (
      <div className="flex flex-col items-start gap-2 text-sm">
        <p className="text-flat">기록된 보유 종목이 없어요.</p>
        <Link to={paths.connect()} className="underline">
          증권 계좌 연동 관리
        </Link>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-2">
      <p className="text-flat text-xs">
        {formatShortDate(data.asOfDate)} 기록 기준 · 손익은 평균 매입가 기준
      </p>
      <ul className="divide-y rounded border">
        {data.holdings.map((h) => (
          <HoldingRow key={`${h.assetId}-${h.ticker}`} holding={h} />
        ))}
      </ul>
    </div>
  )
}

function HoldingRow({ holding: h }: { holding: PortfolioHoldingItem }) {
  // 원금을 알 수 없으면(평균 매입가 없음 · 지금 보유 목록에 없음 등) 손익 · 손익률을 보여주지 않는다
  const hasPrincipal = h.principal != null
  const account = [h.institutionName, h.accountNumberMasked]
    .filter(Boolean)
    .join(' ')

  return (
    <li className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-0.5 px-3 py-2 text-sm">
      <p className="truncate font-medium">{h.stockName}</p>
      <p className="text-right font-medium tabular-nums">
        {formatKRW(h.evalAmount)}
      </p>
      <p className="text-flat truncate text-xs">{account || h.ticker}</p>
      <p
        className={cn(
          'text-right text-xs tabular-nums',
          toneClass(hasPrincipal ? h.profitLoss : null),
        )}
      >
        {hasPrincipal
          ? `${formatSignedKRW(h.profitLoss)} (${formatRate(h.profitLossRate)})`
          : '손익 —'}
      </p>
      <p className="text-flat text-xs">당일</p>
      <p
        className={cn(
          'text-right text-xs tabular-nums',
          toneClass(h.dayChangeAmount),
        )}
      >
        {formatSignedKRW(h.dayChangeAmount)}
      </p>
    </li>
  )
}
