import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import {
  Navigate,
  useLocation,
  useNavigate,
  type Location,
} from 'react-router-dom'
import { paths } from '@/app/paths'
import { login } from '@/features/auth/api'
import { useAuthStore } from '@/features/auth/authStore'
import { errorMessage } from '@/features/auth/session'

export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const status = useAuthStore((state) => state.status)
  const signIn = useAuthStore((state) => state.signIn)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')

  // 가드가 보낸 원래 경로로 돌아간다
  const from =
    (location.state as { from?: Location } | null)?.from?.pathname ??
    paths.dashboard()

  const mutation = useMutation({
    mutationFn: login,
    onSuccess: (result) => {
      signIn(result.accessToken, result.nickname)
      navigate(from, { replace: true })
    },
  })

  if (status === 'authenticated' && !mutation.isPending) {
    return <Navigate to={from} replace />
  }

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    mutation.mutate({ email: email.trim(), password })
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-sm flex-col justify-center gap-6 p-4">
      <h1 className="text-2xl font-bold">로그인</h1>
      <form className="flex flex-col gap-3" onSubmit={onSubmit}>
        <label className="flex flex-col gap-1 text-sm">
          이메일
          <input
            className="rounded border px-3 py-2"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          비밀번호
          <input
            className="rounded border px-3 py-2"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {mutation.isError && (
          <p role="alert" className="text-rise text-sm">
            {errorMessage(
              mutation.error,
              '로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
            )}
          </p>
        )}
        <button
          type="submit"
          className="rounded bg-black px-3 py-2 text-white disabled:opacity-50"
          disabled={mutation.isPending}
        >
          {mutation.isPending ? '로그인 중…' : '로그인'}
        </button>
      </form>
    </main>
  )
}
