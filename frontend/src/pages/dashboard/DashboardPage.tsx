import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { paths } from '@/app/paths'
import AssetComposition from '@/features/asset/AssetComposition'
import AssetSummary from '@/features/asset/AssetSummary'
import AssetTrendChart from '@/features/asset/AssetTrendChart'
import HoldingList from '@/features/asset/HoldingList'
import NoRecordsNotice from '@/features/asset/NoRecordsNotice'
import { useAssetChanges } from '@/features/asset/queries'
import { signOutSession } from '@/features/auth/session'

/**
 * S-01 대시보드 (03 §2). 영역 순서는 03 표 그대로 — 아직 만들지 않은 영역은 화면에 그리지 않고 자리만 남긴다.
 */
export default function DashboardPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  // ②와 같은 조회(캐시 공유) — 기록 유무 판단에만 쓴다
  const changes = useAssetChanges()
  const noRecords = changes.data != null && changes.data.asOfDate == null

  const onLogout = async () => {
    await signOutSession()
    // 다른 계정으로 로그인했을 때 이전 사용자 데이터가 보이지 않게 비운다
    queryClient.clear()
    navigate(paths.login(), { replace: true })
  }

  return (
    <div className="mx-auto flex max-w-xl flex-col gap-6 p-4">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">대시보드</h1>
        <button
          type="button"
          className="rounded border px-3 py-1 text-sm"
          onClick={() => void onLogout()}
        >
          로그아웃
        </button>
      </header>

      {/* 기록이 하나도 없다고 확인된 경우에만 영역 대신 안내를 보여준다. 불러오는 중 · 오류면 영역별 상태를 그대로 보여준다 */}
      {noRecords ? (
        <NoRecordsNotice />
      ) : (
        <>
          {/* ① 오늘의 변화 카드 — 종목 급등락 원인 분석(S-04) 기능에서 추가 */}
          <AssetSummary />
          <AssetTrendChart />
          {/* ④ 목표 진척 게이지 — 투자 목표(S-06) 기능에서 추가 */}
          {/* ⑤ · ⑥은 같은 조회(/snapshots/portfolio)를 공유한다 — 요청 1회 */}
          <AssetComposition />
          <HoldingList />
          {/* ⑦ 결산 배너(매월 1~7일) — 월간 결산(S-03) 기능에서 추가 */}
          {/* ⑧ 예적금 배너 — 예적금 비교(S-07) 기능에서 추가 */}
        </>
      )}
    </div>
  )
}
