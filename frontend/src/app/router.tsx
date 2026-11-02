import { Route, Routes } from 'react-router-dom'
import BottomTabLayout from '@/shared/components/layout/BottomTabLayout'
import RequireAuth from '@/features/auth/RequireAuth'
import LoginPage from '@/pages/login/LoginPage'
import DashboardPage from '@/pages/dashboard/DashboardPage'
import RecordsPage from '@/pages/records/RecordsPage'
import ReportsPage from '@/pages/reports/ReportsPage'
import ReportDetailPage from '@/pages/reportDetail/ReportDetailPage'
import AnalysisPage from '@/pages/analysis/AnalysisPage'
import StockDetailPage from '@/pages/stockDetail/StockDetailPage'
import GoalsPage from '@/pages/goals/GoalsPage'
import GoalDetailPage from '@/pages/goalDetail/GoalDetailPage'
import DepositsComparePage from '@/pages/depositsCompare/DepositsComparePage'
import NotificationsPage from '@/pages/notifications/NotificationsPage'
import NotificationSettingsPage from '@/pages/notificationSettings/NotificationSettingsPage'
import ConnectPage from '@/pages/connect/ConnectPage'
import OnboardingPage from '@/pages/onboarding/OnboardingPage'
import NotFoundPage from '@/pages/notFound/NotFoundPage'

export function AppRouter() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/onboarding" element={<OnboardingPage />} />

      {/* 로그인이 필요한 화면 — 비로그인이면 /login으로 */}
      <Route element={<RequireAuth />}>
        <Route element={<BottomTabLayout />}>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/records" element={<RecordsPage />} />
          <Route path="/reports" element={<ReportsPage />} />
          <Route path="/reports/:yearMonth" element={<ReportDetailPage />} />
          <Route path="/analysis/:stockCode/:date" element={<AnalysisPage />} />
          <Route path="/stocks/:stockCode" element={<StockDetailPage />} />
          <Route path="/goals" element={<GoalsPage />} />
          <Route path="/goals/:goalId" element={<GoalDetailPage />} />
          <Route path="/deposits/compare" element={<DepositsComparePage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route
            path="/settings/notifications"
            element={<NotificationSettingsPage />}
          />
          <Route path="/connect" element={<ConnectPage />} />
        </Route>
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
