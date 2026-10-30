/** 백엔드 공통 응답 envelope (global/response/ApiResponse) */
export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message: string;
  errorCode: string | null;
  timestamp: string;
}
