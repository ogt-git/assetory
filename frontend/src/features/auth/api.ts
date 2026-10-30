import { apiClient } from '@/shared/api/client';
import type { ApiResponse } from '@/shared/api/types';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  userId: number;
  accessToken: string;
  nickname: string;
  email: string;
}

interface TokenResponse {
  accessToken: string;
}

// RT는 응답 쿠키로 받는다(본문에 없음)
export async function login(request: LoginRequest): Promise<LoginResponse> {
  const { data } = await apiClient.post<ApiResponse<LoginResponse>>(
    '/auth/login',
    request,
  );
  return data.data;
}

export async function refresh(): Promise<string> {
  const { data } =
    await apiClient.post<ApiResponse<TokenResponse>>('/auth/refresh');
  return data.data.accessToken;
}

export async function logout(): Promise<void> {
  await apiClient.post('/auth/logout');
}
