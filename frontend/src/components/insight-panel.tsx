import { InsightBadge } from './insight-badge'
import type { Insight } from '@/lib/api'

export const InsightPanel = ({ insight, live }: { insight: Insight; live: boolean }) => {
  if (!insight.found) {
    return (
      <section className="rounded border bg-white p-4">
        <h2 className="mb-2 font-medium">Latest insight</h2>
        <p className="text-sm text-gray-400">
          No insight stored for this ticker yet — trigger a data refresh to generate one.
        </p>
      </section>
    )
  }

  return (
    <section className="rounded border bg-white p-4">
      <div className="mb-2 flex items-center gap-2">
        <h2 className="font-medium">Latest insight</h2>
        <InsightBadge signal={insight.signal} confidence={insight.confidence} />
        {live && (
          <span className="rounded bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-800">
            LIVE
          </span>
        )}
      </div>
      <p className="mb-2 text-sm">{insight.insightText}</p>
      {insight.drivers.length > 0 && (
        <ul className="mb-2 flex flex-wrap gap-1">
          {insight.drivers.map((driver) => (
            <li key={driver} className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600">
              {driver}
            </li>
          ))}
        </ul>
      )}
      <p className="text-xs text-gray-400">
        {insight.source} · {insight.modelId} · {insight.generatedAt}
      </p>
    </section>
  )
}
