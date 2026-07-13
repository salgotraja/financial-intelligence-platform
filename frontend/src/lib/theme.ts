// Chart hexes duplicated from globals.css (:root) because Recharts writes SVG
// attributes, where CSS var() does not resolve. Keep in sync.
export const CHART_COLORS = {
  line: '#38d6f5',
  grid: '#1f2534',
  tick: '#8a93a8',
  up: '#2fd48f',
  down: '#ff6b6b',
} as const
