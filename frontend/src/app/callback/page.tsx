'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { useAuthStore } from '@/stores/auth-store'

export default function CallbackPage() {
  const status = useAuthStore((s) => s.status)
  const router = useRouter()

  useEffect(() => {
    if (status === 'signed-in') router.replace('/dashboard')
    if (status === 'signed-out') router.replace('/')
  }, [status, router])

  return <main className="p-8 text-gray-500">Completing sign-in…</main>
}
