export const paths = {
  login: () => '/login',
  dashboard: () => '/',
  records: (date?: string) => (date ? `/records?date=${date}` : '/records'),
  reports: () => '/reports',
  reportDetail: (yearMonth: string) => `/reports/${yearMonth}`,
  analysis: (stockCode: string, date: string) =>
    `/analysis/${stockCode}/${date}`,
  stockDetail: (stockCode: string) => `/stocks/${stockCode}`,
  goals: () => '/goals',
  goalDetail: (goalId: string) => `/goals/${goalId}`,
  depositsCompare: () => '/deposits/compare',
  notifications: () => '/notifications',
  notificationSettings: () => '/settings/notifications',
  connect: () => '/connect',
  onboarding: () => '/onboarding',
} as const
