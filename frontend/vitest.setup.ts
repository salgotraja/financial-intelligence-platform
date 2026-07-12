process.env.NEXT_PUBLIC_API_URL ??= 'https://api.test/dev'
process.env.NEXT_PUBLIC_WS_URL ??= 'wss://ws.test/dev'
process.env.NEXT_PUBLIC_USER_POOL_ID ??= 'ap-south-1_test'
process.env.NEXT_PUBLIC_USER_POOL_CLIENT_ID ??= 'client-test'
process.env.NEXT_PUBLIC_COGNITO_DOMAIN ??= 'test.auth.ap-south-1.amazoncognito.com'
process.env.NEXT_PUBLIC_SIGNIN_REDIRECT ??= 'http://localhost:3000/callback'
process.env.NEXT_PUBLIC_SIGNOUT_REDIRECT ??= 'http://localhost:3000'

import '@testing-library/jest-dom/vitest'
