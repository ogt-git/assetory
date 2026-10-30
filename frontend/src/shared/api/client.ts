import axios, { AxiosHeaders, type InternalAxiosRequestConfig } from 'axios';

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 10000,
  // RT는 HttpOnly 쿠키(Path /api/auth) — 개발 서버는 /api 프록시라 같은 출처지만, 출처가 달라져도 쿠키가 실리게 한다
  withCredentials: true,
});

/**
 * 인증 연결점 — shared는 features를 모르므로 features/auth가 앱 시작 시 등록한다.
 * - getAccessToken: 메모리의 AT
 * - refreshAccessToken: 새 AT 발급(진행 중 요청 공유는 등록하는 쪽 책임). 실패하면 reject
 * - onRefreshFailed: refresh 실패 시 세션 정리
 */
interface AuthHandlers {
  getAccessToken: () => string | null;
  refreshAccessToken: () => Promise<string>;
  onRefreshFailed: () => void;
}

let handlers: AuthHandlers | null = null;

export function setAuthHandlers(next: AuthHandlers) {
  handlers = next;
}

// 이 경로들의 401은 refresh로 복구하지 않는다(로그인 실패 · refresh 실패 자체)
const NO_RETRY_PATHS = ['/auth/login', '/auth/refresh', '/auth/logout'];

type RetryableConfig = InternalAxiosRequestConfig & { _retried?: boolean };

apiClient.interceptors.request.use((config) => {
  const token = handlers?.getAccessToken();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

apiClient.interceptors.response.use(undefined, async (error: unknown) => {
  if (!axios.isAxiosError(error) || !handlers) throw error;
  const config = error.config as RetryableConfig | undefined;
  const skip =
    !config ||
    config._retried ||
    NO_RETRY_PATHS.some((path) => config.url?.startsWith(path));
  if (error.response?.status !== 401 || skip) throw error;

  config._retried = true;
  let token: string;
  try {
    token = await handlers.refreshAccessToken();
  } catch {
    handlers.onRefreshFailed();
    throw error;
  }
  const headers = AxiosHeaders.from(config.headers);
  headers.set('Authorization', `Bearer ${token}`);
  return apiClient({ ...config, headers });
});
