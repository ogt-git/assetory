import { Link } from 'react-router-dom'
import { paths } from '@/app/paths'

/** 알림 — 알림 센터는 12주차. 지금은 S-09 알림 설정으로 가는 입구만 둔다 (UC-07 트리거 "알림 화면의 '설정 조정'") */
export default function NotificationsPage() {
  return (
    <div className="mx-auto flex max-w-xl flex-col gap-4 p-4">
      <div className="flex items-baseline justify-between gap-2">
        <h1 className="text-xl font-bold">알림</h1>
        <Link
          to={paths.notificationSettings()}
          className="text-sm underline underline-offset-2"
        >
          알림 설정
        </Link>
      </div>
    </div>
  )
}
