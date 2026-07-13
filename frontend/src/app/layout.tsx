import type { Metadata } from 'next'
import { Geist, Geist_Mono } from 'next/font/google'
import './globals.css'
import { AuthProvider } from '@/components/auth-provider'
import { NavBar } from '@/components/nav-bar'

const geistSans = Geist({
  variable: '--font-geist-sans',
  subsets: ['latin'],
})

const geistMono = Geist_Mono({
  variable: '--font-geist-mono',
  subsets: ['latin'],
})

export const metadata: Metadata = {
  title: 'Financial Intelligence Platform',
  description: 'Realtime market insights',
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable}`}>
      <body className="min-h-screen bg-background font-sans text-foreground antialiased">
        <AuthProvider>
          <NavBar />
          <div className="mx-auto max-w-6xl px-4 py-8 sm:px-6">{children}</div>
        </AuthProvider>
      </body>
    </html>
  )
}
