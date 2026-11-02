import { create } from 'zustand'

/**
 * 인증 상태 — AT는 메모리에만 둔다(localStorage · sessionStorage 금지). 새로고침하면 사라지고 RT 쿠키로 다시 받는다.
 * - unknown: 앱 시작 직후, refresh로 복구를 시도하기 전
 * - authenticated: AT 보유
 * - anonymous: 로그인 필요
 */
type AuthStatus = 'unknown' | 'authenticated' | 'anonymous'

interface AuthState {
  status: AuthStatus
  accessToken: string | null
  nickname: string | null
  signIn: (accessToken: string, nickname: string | null) => void
  updateAccessToken: (accessToken: string) => void
  signOut: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  status: 'unknown',
  accessToken: null,
  nickname: null,
  signIn: (accessToken, nickname) =>
    set({ status: 'authenticated', accessToken, nickname }),
  updateAccessToken: (accessToken) =>
    set({ status: 'authenticated', accessToken }),
  signOut: () =>
    set({ status: 'anonymous', accessToken: null, nickname: null }),
}))
