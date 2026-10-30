import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { paths } from '@/app/paths'
import { fetchAssetChanges } from '@/features/asset/api'
import { useAuthStore } from '@/features/auth/authStore'
import { signOutSession } from '@/features/auth/session'
import { formatKRW } from '@/shared/lib/format'

export default function DashboardPage() {
  const nickname = useAuthStore((state) => state.nickname)
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const changes = useQuery({
    queryKey: ['snapshots', 'changes'],
    queryFn: fetchAssetChanges,
  })

  const onLogout = async () => {
    await signOutSession()
    // 다른 계정으로 로그인했을 때 이전 사용자 데이터가 보이지 않게 비운다
    queryClient.clear()
    navigate(paths.login(), { replace: true })
  }

  return (
    <div className="flex flex-col gap-4 p-4">
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
      {nickname && <p className="text-sm">{nickname}님</p>}

      {/* C1 임시 확인 영역 — 로그인 · 토큰 처리 확인용. C2(S-01 대시보드)에서 교체한다 */}
      <section
        aria-label="자산 요약(임시)"
        className="rounded border p-3 text-sm"
      >
        {changes.isPending && <p>불러오는 중…</p>}
        {changes.isError && (
          <p className="text-rise">자산 정보를 불러오지 못했습니다.</p>
        )}
        {changes.data && (
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
            <dt>기준일</dt>
            <dd>{changes.data.asOfDate ?? '—'}</dd>
            <dt>총자산</dt>
            <dd>{formatKRW(changes.data.totalAsset)}</dd>
            <dt>최근 기록</dt>
            <dd>
              {changes.data.latestRecord
                ? `${changes.data.latestRecord.date} (${changes.data.latestRecord.status})`
                : '기록 없음'}
            </dd>
          </dl>
        )}
      </section>
    </div>
  )
}
