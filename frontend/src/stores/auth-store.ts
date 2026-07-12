import { create } from 'zustand'
import { getSessionInfo } from '@/lib/auth'

export type AuthStatus = 'loading' | 'signed-in' | 'signed-out'

interface AuthState {
  status: AuthStatus
  sub: string | null
  email: string | null
  groups: string[]
  refresh: () => Promise<void>
  markSignedOut: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  status: 'loading',
  sub: null,
  email: null,
  groups: [],
  refresh: async () => {
    const info = await getSessionInfo()
    if (info) {
      set({ status: 'signed-in', sub: info.sub, email: info.email, groups: info.groups })
    } else {
      set({ status: 'signed-out', sub: null, email: null, groups: [] })
    }
  },
  markSignedOut: () => set({ status: 'signed-out', sub: null, email: null, groups: [] }),
}))
