// NSE regular equity session, treated as 09:00-15:35 IST, Mon-Fri. Holidays are not
// modelled (weekday + time only), so the market may read "open" on an NSE holiday.
// IST is a fixed UTC+5:30 with no DST, so wall-clock <-> instant conversion is exact
// arithmetic and needs no timezone library.

const IST_OFFSET_MIN = 330
const OPEN_MIN = 9 * 60 // 09:00 IST
const CLOSE_MIN = 15 * 60 + 35 // 15:35 IST
const DAY_MS = 86_400_000

interface IstParts {
  year: number
  month: number // 1-12
  day: number
  weekday: number // 0=Sun .. 6=Sat
  minutesOfDay: number
}

const istParts = (now: Date): IstParts => {
  const shifted = new Date(now.getTime() + IST_OFFSET_MIN * 60_000)
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth() + 1,
    day: shifted.getUTCDate(),
    weekday: shifted.getUTCDay(),
    minutesOfDay: shifted.getUTCHours() * 60 + shifted.getUTCMinutes(),
  }
}

const isWeekday = (weekday: number): boolean => weekday >= 1 && weekday <= 5

/** True when `now` falls inside the current NSE session window (weekday + time only). */
export const isMarketOpen = (now: Date): boolean => {
  const { weekday, minutesOfDay } = istParts(now)
  return isWeekday(weekday) && minutesOfDay >= OPEN_MIN && minutesOfDay <= CLOSE_MIN
}

export interface SessionWindow {
  startMs: number
  endMs: number
}

/**
 * The most recent NSE session as an absolute [startMs, endMs] instant range: today's
 * session once it has begun (in-progress or already closed), otherwise the previous
 * trading day's session.
 */
export const latestSession = (now: Date): SessionWindow => {
  const parts = istParts(now)
  const startedToday = isWeekday(parts.weekday) && parts.minutesOfDay >= OPEN_MIN

  // UTC midnight of the IST calendar date; date-only, so the timezone is irrelevant
  // for weekday arithmetic.
  let cursorMs = Date.UTC(parts.year, parts.month - 1, parts.day)
  if (!startedToday) {
    do {
      cursorMs -= DAY_MS
    } while (!isWeekday(new Date(cursorMs).getUTCDay()))
  }

  const istMidnightUtc = cursorMs - IST_OFFSET_MIN * 60_000
  return {
    startMs: istMidnightUtc + OPEN_MIN * 60_000,
    endMs: istMidnightUtc + CLOSE_MIN * 60_000,
  }
}

/** Points whose timestamp falls within the latest NSE session, newest-first order kept. */
export const sessionPoints = <T extends { timestamp: string }>(points: T[], now: Date): T[] => {
  const { startMs, endMs } = latestSession(now)
  return points.filter((p) => {
    const t = new Date(p.timestamp).getTime()
    return t >= startMs && t <= endMs
  })
}

/**
 * Intraday points for charting: the latest session's points, falling back to every
 * available point when the session window holds fewer than two, so a chart degrades
 * gracefully instead of blanking.
 */
export const intradaySessionPoints = <T extends { timestamp: string }>(
  points: T[],
  now: Date,
): T[] => {
  const session = sessionPoints(points, now)
  return session.length >= 2 ? session : points
}
