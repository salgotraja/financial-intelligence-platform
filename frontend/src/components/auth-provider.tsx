'use client'

import { ReactNode, useEffect } from 'react'
import { Hub } from 'aws-amplify/utils'
import { configureAmplify } from '@/lib/amplify'
import { useAuthStore } from '@/stores/auth-store'

configureAmplify()

export const AuthProvider = ({ children }: { children: ReactNode }) => {
  const refresh = useAuthStore((s) => s.refresh)
  const markSignedOut = useAuthStore((s) => s.markSignedOut)

  useEffect(() => {
    void refresh()
    const unsubscribe = Hub.listen('auth', ({ payload }) => {
      switch (payload.event) {
        case 'signedIn':
        case 'signInWithRedirect':
        case 'tokenRefresh':
          void refresh()
          break
        case 'signedOut':
        case 'signInWithRedirect_failure':
        case 'tokenRefresh_failure':
          markSignedOut()
          break
      }
    })
    return unsubscribe
  }, [refresh, markSignedOut])

  return <>{children}</>
}
