import { NavLink, Outlet } from 'react-router-dom'
import { paths } from '@/app/paths'
import { cn } from '@/shared/lib/cn'

const tabs = [
  { label: '홈', to: paths.dashboard() },
  { label: '기록', to: paths.records() },
  { label: '결산', to: paths.reports() },
  { label: '목표', to: paths.goals() },
  { label: '알림', to: paths.notifications() },
]

export default function BottomTabLayout() {
  return (
    <div>
      <main>
        <Outlet />
      </main>
      <nav>
        {tabs.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            className={({ isActive }) => cn(isActive && 'active')}
            end
          >
            {tab.label}
          </NavLink>
        ))}
      </nav>
    </div>
  )
}
