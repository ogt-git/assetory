import type {
  AssetType,
  SnapshotDetailAssetClassItem,
  SnapshotDetailAssetItem,
  SnapshotDetailHoldingItem,
  SnapshotDetailResponse,
} from '@/features/asset/api'
import { useSnapshotDetail } from '@/features/asset/queries'
import { cn } from '@/shared/lib/cn'
import {
  formatKRW,
  formatShortDate,
  formatSignedKRW,
} from '@/shared/lib/format'

function toneClass(value: number | null) {
  if (value == null || value === 0) return 'text-flat'
  return value > 0 ? 'text-rise' : 'text-fall'
}

/** 수량 표시 — 소수 주식(최대 4자리)까지 그대로. 계산이 아니라 표기만 바꾼다 */
function formatQuantity(quantity: number | null) {
  if (quantity == null) return '—'
  return `${quantity.toLocaleString('ko-KR', { maximumFractionDigits: 4 })}주`
}

// 자산 1행의 금액이 무엇인지 — 증권 계좌 행은 예수금, 나머지는 수기 자산 잔액
const AMOUNT_LABELS: Record<AssetType, string> = {
  SECURITIES: '예수금',
  DEPOSIT: '예금',
  SAVINGS: '적금',
  CHECKING: '입출금',
  CASH: '현금',
}

/**
 * S-02 날짜 행 펼침 — 그날의 자산군 → 자산 → 종목 기록 (FR-AS-06, FR-AN-01 종목 단위).
 * 값은 /snapshots/{date} 그대로. 증감 · 수량 · 단가가 없으면(전환기 기록 등) "—".
 */
export default function RecordDetail({ date }: { date: string }) {
  const detail = useSnapshotDetail(date, true)

  return (
    <div className="border-t bg-neutral-50 px-3 py-3 text-sm">
      {detail.isPending && <p className="text-flat">불러오는 중…</p>}
      {detail.isError && (
        // 이 행의 오류만 보여준다 — 목록과 다른 행은 그대로
        <div className="flex items-center gap-2">
          <p className="text-rise">이 날의 상세를 불러오지 못했어요.</p>
          <button
            type="button"
            className="rounded border bg-white px-2 py-0.5"
            onClick={() => void detail.refetch()}
          >
            다시 시도
          </button>
        </div>
      )}
      {detail.data && <DetailBody data={detail.data} />}
    </div>
  )
}

function DetailBody({ data }: { data: SnapshotDetailResponse }) {
  // 누락 날은 목록에서 상세를 부르지 않지만, 응답이 누락이면 부분 금액 없이 안내만
  if (data.missing || !data.assetClasses) {
    return <p className="text-partial text-xs">비교에서 제외된 날이에요.</p>
  }
  const classes = data.assetClasses

  return (
    <div className="flex flex-col gap-3">
      <p className="text-flat text-xs">
        {data.baseDate
          ? `${formatShortDate(data.baseDate, data.date)} 대비`
          : '비교할 이전 기록이 없어요'}
      </p>

      <dl className="grid grid-cols-[1fr_auto_auto] gap-x-3 gap-y-1">
        <ClassRow label="증권" item={classes.securities} />
        <ClassRow label="예적금" item={classes.deposit} />
        <ClassRow label="현금" item={classes.cash} />
      </dl>

      {data.assets.length > 0 && (
        <ul className="flex flex-col gap-2">
          {data.assets.map((asset) => (
            <AssetRow key={asset.assetId} asset={asset} />
          ))}
        </ul>
      )}
    </div>
  )
}

function ClassRow({
  label,
  item,
}: {
  label: string
  item: SnapshotDetailAssetClassItem
}) {
  return (
    <>
      <dt>{label}</dt>
      <dd className="text-right tabular-nums">{formatKRW(item.amount)}</dd>
      <dd
        className={cn(
          'w-24 text-right tabular-nums',
          toneClass(item.changeAmount),
        )}
      >
        {formatSignedKRW(item.changeAmount)}
      </dd>
    </>
  )
}

function AssetRow({ asset }: { asset: SnapshotDetailAssetItem }) {
  const title = [asset.name, asset.accountNumberMasked]
    .filter(Boolean)
    .join(' ')

  return (
    <li className="rounded border bg-white">
      <div className="grid grid-cols-[1fr_auto] gap-x-3 px-3 py-2">
        <p className="truncate font-medium">{title}</p>
        <p className="text-right tabular-nums">{formatKRW(asset.amount)}</p>
        <p className="text-flat text-xs">{AMOUNT_LABELS[asset.assetType]}</p>
        <p
          className={cn(
            'text-right text-xs tabular-nums',
            toneClass(asset.dayChangeAmount),
          )}
        >
          {formatSignedKRW(asset.dayChangeAmount)}
        </p>
      </div>
      {asset.holdings.length > 0 && (
        <ul className="divide-y border-t">
          {asset.holdings.map((holding) => (
            <HoldingRow key={holding.ticker} holding={holding} />
          ))}
        </ul>
      )}
    </li>
  )
}

/** 종목 1행 — 좁은 화면에서도 가로 스크롤이 없게 2줄로 쌓는다(종목명 · 금액 / 수량 × 단가 · 전일 대비) */
function HoldingRow({ holding: h }: { holding: SnapshotDetailHoldingItem }) {
  return (
    <li className="grid grid-cols-[1fr_auto] gap-x-3 px-3 py-1.5">
      <p className="truncate">{h.stockName}</p>
      <p className="text-right tabular-nums">{formatKRW(h.amount)}</p>
      <p className="text-flat text-xs tabular-nums">
        {formatQuantity(h.quantity)} × {formatKRW(h.price)}
      </p>
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
