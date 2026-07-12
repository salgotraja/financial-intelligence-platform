import { fetchAuthSession } from 'aws-amplify/auth'

const MANAGER_GROUPS = ['premium', 'admins']

export interface SessionInfo {
  sub: string
  email: string | null
  username: string | null
  groups: string[]
}

export const parseGroups = (payload: Record<string, unknown> | undefined): string[] => {
  const claim = payload?.['cognito:groups']
  if (!Array.isArray(claim)) return []
  return claim.filter((g): g is string => typeof g === 'string')
}

export const canManageWatchlist = (groups: string[]): boolean =>
  groups.some((g) => MANAGER_GROUPS.includes(g))

export const getAccessToken = async (): Promise<string | null> => {
  try {
    const session = await fetchAuthSession()
    return session.tokens?.accessToken?.toString() ?? null
  } catch {
    return null
  }
}

export const getSessionInfo = async (): Promise<SessionInfo | null> => {
  try {
    const session = await fetchAuthSession()
    const token = session.tokens?.accessToken
    if (!token) return null
    const payload = token.payload as Record<string, unknown>
    return {
      sub: typeof payload.sub === 'string' ? payload.sub : '',
      email: typeof payload.email === 'string' ? payload.email : null,
      username: typeof payload.username === 'string' ? payload.username : null,
      groups: parseGroups(payload),
    }
  } catch {
    return null
  }
}
