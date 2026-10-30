import { apiClient } from '@/shared/api/client';
import type { ApiResponse } from '@/shared/api/types';

export type SnapshotStatus = 'COMPLETE' | 'PARTIAL' | 'FAILED';

/** GET /api/snapshots/changes — C1 임시 확인에 쓰는 필드만. 전체 타입은 C2에서 정의한다 */
export interface AssetChangesSummary {
  asOfDate: string | null;
  totalAsset: number | null;
  latestRecord: { date: string; status: SnapshotStatus } | null;
}

export async function fetchAssetChanges(): Promise<AssetChangesSummary> {
  const { data } =
    await apiClient.get<ApiResponse<AssetChangesSummary>>('/snapshots/changes');
  return data.data;
}
