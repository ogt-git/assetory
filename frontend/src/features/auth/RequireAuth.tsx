import { useEffect } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { paths } from '@/app/paths'
import { useAuthStore } from '@/features/auth/authStore'
import { restoreSession } from '@/features/auth/session'

/** 로그인이 필요한 화면의 가드 — 처음에는 RT 쿠키로 복구를 시도하고, 실패하면 /login으로 보낸다 */
export default function RequireAuth() {
  const status = useAuthStore((state) => state.status)
  const location = useLocation()

  useEffect(() => {
    void restoreSession()
  }, [])

  if (status === 'unknown') {
    return (
      <p className="text-flat p-4 text-sm">로그인 상태를 확인하고 있습니다…</p>
    )
  }
  if (status === 'anonymous') {
    return <Navigate to={paths.login()} replace state={{ from: location }} />
  }
  return <Outlet />
}
