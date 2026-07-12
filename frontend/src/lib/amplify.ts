import { Amplify } from 'aws-amplify'
import { appConfig } from './config'

let configured = false

export const configureAmplify = (): void => {
  if (configured) return
  Amplify.configure({
    Auth: {
      Cognito: {
        userPoolId: appConfig.userPoolId,
        userPoolClientId: appConfig.userPoolClientId,
        loginWith: {
          oauth: {
            domain: appConfig.cognitoDomain,
            scopes: ['openid', 'email', 'profile'],
            redirectSignIn: [appConfig.signInRedirect],
            redirectSignOut: [appConfig.signOutRedirect],
            responseType: 'code',
          },
        },
      },
    },
  })
  configured = true
}
