import { Link } from 'react-router-dom'
import { paths } from '@/app/paths'

/**
 * 자산 기록이 하나도 없을 때(연동 · 등록 전, 또는 첫 정기 기록 전) 대시보드 대신 보여준다.
 * 기록은 연동 뒤 첫 정기 기록부터 생긴다(FR-AS-05) — 과거 기록을 만들어 채우지 않는다.
 */
export default function NoRecordsNotice() {
  return (
    <section
      aria-labelledby="no-records-title"
      className="flex flex-col items-start gap-3 rounded border p-4"
    >
      <h2 id="no-records-title" className="font-semibold">
        아직 자산 기록이 없어요
      </h2>
      <p className="text-flat text-sm">
        계좌를 연동하거나 예적금 · 현금을 등록하면 다음 거래일 기록부터 총자산
        변화를 보여드려요.
      </p>
      <Link
        to={paths.connect()}
        className="rounded bg-black px-3 py-2 text-sm text-white"
      >
        계좌 연동하기
      </Link>
    </section>
  )
}
