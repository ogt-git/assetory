import axios from 'axios';
import { setAuthHandlers } from '@/shared/api/client';
import * as authApi from '@/features/auth/api';
import { useAuthStore } from '@/features/auth/authStore';

/**
 * 진행 중인 refresh 요청 — 여러 곳에서 동시에 불러도 요청은 하나만 보낸다.
 * RT는 회전되므로 같은 RT로 두 번 요청하면 서버가 재사용으로 보고 세션 전체를 폐기한다(05 §3.3).
 */
let inFlight: Promise<string> | null = null;

export function refreshSession(): Promise<string> {
  if (!inFlight) {
    inFlight = authApi
      .refresh()
      .then((token) => {
        useAuthStore.getState().updateAccessToken(token);
        return token;
      })
      .finally(() => {
        inFlight = null;
      });
  }
  return inFlight;
}

/** 앱 시작 직후 한 번 — 남아 있는 RT 쿠키로 AT 복구를 시도한다 */
export async function restoreSession(): Promise<void> {
  if (useAuthStore.getState().status !== 'unknown') return;
  try {
    await refreshSession();
  } catch {
    useAuthStore.getState().signOut();
  }
}

export async function signOutSession(): Promise<void> {
  try {
    await authApi.logout();
  } finally {
    // 서버 로그아웃이 실패해도(AT 만료 등) 화면 세션은 정리한다
    useAuthStore.getState().signOut();
  }
}

/** API 클라이언트에 인증 처리를 연결한다 — main.tsx에서 한 번 */
export function setupAuth() {
  setAuthHandlers({
    getAccessToken: () => useAuthStore.getState().accessToken,
    refreshAccessToken: refreshSession,
    onRefreshFailed: () => useAuthStore.getState().signOut(),
  });
}

/** 서버 오류 응답의 사용자 문구 — 없으면(네트워크 오류 등) 기본 문구 */
export function errorMessage(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const message = (error.response?.data as { message?: unknown } | undefined)
      ?.message;
    if (typeof message === 'string' && message) return message;
  }
  return fallback;
}
