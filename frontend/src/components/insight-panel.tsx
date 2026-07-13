import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { SignalBadge } from './signal-badge'
import { formatTimestamp } from '@/lib/format'
import type { Insight } from '@/lib/api'

export const InsightPanel = ({
  insight,
  live,
}: {
  insight: Insight
  live: boolean
}) => {
  if (!insight.found) {
    return (
      <Card className="border-border bg-card">
        <CardHeader>
          <CardTitle className="text-sm font-medium">Latest insight</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            No insight stored for this ticker yet — trigger a data refresh to
            generate one.
          </p>
        </CardContent>
      </Card>
    )
  }

  return (
    <Card className="border-border bg-card">
      <CardHeader className="flex flex-row items-center gap-2">
        <CardTitle className="text-sm font-medium">Latest insight</CardTitle>
        <SignalBadge
          signal={insight.signal}
          confidence={insight.confidence}
          source={null}
        />
        {live && (
          <Badge
            variant="outline"
            className="border-primary/40 text-[10px] text-primary"
          >
            LIVE
          </Badge>
        )}
      </CardHeader>
      <CardContent className="space-y-2">
        <p className="text-sm leading-relaxed">{insight.insightText}</p>
        {insight.drivers.length > 0 && (
          <ul className="flex flex-wrap gap-1">
            {insight.drivers.map((driver) => (
              <li
                key={driver}
                className="rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground"
              >
                {driver}
              </li>
            ))}
          </ul>
        )}
        <p className="font-mono text-xs text-muted-foreground">
          {[
            insight.source,
            // A rule-based fallback did not use the model, so citing a model id is misleading.
            insight.source === 'RULE_BASED' ? null : insight.modelId,
            formatTimestamp(insight.generatedAt),
          ]
            .filter(Boolean)
            .join(' · ')}
        </p>
      </CardContent>
    </Card>
  )
}
