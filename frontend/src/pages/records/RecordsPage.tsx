import RecordsMonth from '@/features/asset/RecordsMonth'

/** S-02 자산 기록 (03 §3) — ?month=yyyy-MM · ?date=yyyy-MM-dd */
export default function RecordsPage() {
  return (
    <div className="mx-auto flex max-w-xl flex-col gap-4 p-4">
      <h1 className="text-xl font-bold">자산 기록</h1>
      <RecordsMonth />
    </div>
  )
}
