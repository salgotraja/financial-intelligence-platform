'use client'

import { ReactNode, useMemo } from 'react'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { getStory } from '@/lib/api'
import { formatTimestamp } from '@/lib/format'
import { useAsyncData } from '@/hooks/use-async-data'

const StoryCard = ({ children }: { children: ReactNode }) => (
  <Card className="border-border bg-card">
    <CardHeader>
      <CardTitle className="text-sm font-medium">Story</CardTitle>
    </CardHeader>
    <CardContent>{children}</CardContent>
  </Card>
)

export const StoryPanel = ({
  symbol,
  enabled,
}: {
  symbol: string
  enabled: boolean
}) => {
  const { data, error, loading } = useAsyncData(
    useMemo(() => () => getStory(symbol), [symbol]),
    enabled,
  )

  if (loading) {
    return (
      <StoryCard>
        <Skeleton className="h-16 w-full" />
      </StoryCard>
    )
  }

  if (error !== null) {
    return (
      <StoryCard>
        <p className="text-sm text-destructive">Could not load story.</p>
      </StoryCard>
    )
  }

  if (data === null) return null

  // `found: false` is the sibling routes' honest-empty-state contract: the backend still
  // returns a story sentence (a fixed fallback), rendered here in the muted empty-state
  // style instead of a separately hardcoded frontend message.
  if (!data.found) {
    return (
      <StoryCard>
        <p className="text-sm text-muted-foreground">{data.story}</p>
      </StoryCard>
    )
  }

  return (
    <Card className="border-border bg-card">
      <CardHeader className="flex flex-row items-center gap-2">
        <CardTitle className="text-sm font-medium">Story</CardTitle>
        {data.source && (
          <Badge variant="outline" className="border-border text-[10px] text-muted-foreground">
            {data.source}
          </Badge>
        )}
      </CardHeader>
      <CardContent className="space-y-2">
        <p className="text-sm leading-relaxed">{data.story}</p>
        <p className="font-mono text-xs text-muted-foreground">
          {formatTimestamp(data.generatedAt)}
        </p>
      </CardContent>
    </Card>
  )
}
