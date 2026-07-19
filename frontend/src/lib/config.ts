const required = (name: string, value: string | undefined): string => {
  if (!value) throw new Error(`Missing env: ${name}`)
  return value
}

export const appConfig = {
  apiUrl: required('NEXT_PUBLIC_API_URL', process.env.NEXT_PUBLIC_API_URL),
  userPoolId: required('NEXT_PUBLIC_USER_POOL_ID', process.env.NEXT_PUBLIC_USER_POOL_ID),
  userPoolClientId: required(
    'NEXT_PUBLIC_USER_POOL_CLIENT_ID',
    process.env.NEXT_PUBLIC_USER_POOL_CLIENT_ID,
  ),
  cognitoDomain: required('NEXT_PUBLIC_COGNITO_DOMAIN', process.env.NEXT_PUBLIC_COGNITO_DOMAIN),
  signInRedirect: required('NEXT_PUBLIC_SIGNIN_REDIRECT', process.env.NEXT_PUBLIC_SIGNIN_REDIRECT),
  signOutRedirect: required(
    'NEXT_PUBLIC_SIGNOUT_REDIRECT',
    process.env.NEXT_PUBLIC_SIGNOUT_REDIRECT,
  ),
} as const

export type AppConfig = typeof appConfig
