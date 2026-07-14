// NSE regular equity session, treated as 09:00-15:35 IST, Mon-Fri, excluding NSE trading
// holidays. IST is a fixed UTC+5:30 with no DST, so wall-clock <-> instant conversion is
// exact arithmetic and needs no timezone library.

const IST_OFFSET_MIN = 330
const OPEN_MIN = 9 * 60 // 09:00 IST
const CLOSE_MIN = 15 * 60 + 35 // 15:35 IST
const DAY_MS = 86_400_000

/**
 * NSE Capital Market segment trading holidays for calendar year 2026, per NSE circular
 * NSE/CMTR/71775 ("Trading holidays for the calendar year 2026", December 12, 2025):
 * https://nsearchives.nseindia.com/content/circulars/CMTR71775.pdf
 * Plus the ad-hoc January 15, 2026 holiday for the Maharashtra Municipal Corporation
 * elections, notified as a partial modification to NSE/CMTR/71775 (~January 12, 2026;
 * corroborated by NSE/CD/72233, January 9, 2026, the equivalent Currency Derivatives
 * notice: https://nsearchives.nseindia.com/content/circulars/CD72233.pdf).
 * Keep in the same chronological order as MarketHours.java's TRADING_HOLIDAYS_2026 so
 * drift between the two lists is a straight visual diff.
 */
const TRADING_HOLIDAYS_2026 = new Set([
  '2026-01-15', // Maharashtra Municipal Corporation Elections
  '2026-01-26', // Republic Day
  '2026-03-03', // Holi
  '2026-03-26', // Shri Ram Navami
  '2026-03-31', // Shri Mahavir Jayanti
  '2026-04-03', // Good Friday
  '2026-04-14', // Dr. Baba Saheb Ambedkar Jayanti
  '2026-05-01', // Maharashtra Day
  '2026-05-28', // Bakri Id
  '2026-06-26', // Muharram
  '2026-09-14', // Ganesh Chaturthi
  '2026-10-02', // Mahatma Gandhi Jayanti
  '2026-10-20', // Dussehra
  '2026-11-10', // Diwali-Balipratipada
  '2026-11-24', // Prakash Gurpurb Sri Guru Nanak Dev
  '2026-12-25', // Christmas
])

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

const pad2 = (n: number): string => String(n).padStart(2, '0')

const dateKey = (year: number, month: number, day: number): string =>
  `${year}-${pad2(month)}-${pad2(day)}`

/** True when a weekday + date key combination is an NSE trading day (not a listed holiday). */
const isTradingDay = (weekday: number, key: string): boolean =>
  isWeekday(weekday) && !TRADING_HOLIDAYS_2026.has(key)

/** True when `now` falls inside the current NSE session window (trading day + time). */
export const isMarketOpen = (now: Date): boolean => {
  const parts = istParts(now)
  if (!isTradingDay(parts.weekday, dateKey(parts.year, parts.month, parts.day))) {
    return false
  }
  return parts.minutesOfDay >= OPEN_MIN && parts.minutesOfDay <= CLOSE_MIN
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
  const todayKey = dateKey(parts.year, parts.month, parts.day)
  const startedToday = isTradingDay(parts.weekday, todayKey) && parts.minutesOfDay >= OPEN_MIN

  // UTC midnight of the IST calendar date; date-only, so the timezone is irrelevant
  // for weekday/holiday arithmetic.
  let cursorMs = Date.UTC(parts.year, parts.month - 1, parts.day)
  if (!startedToday) {
    let cursorIsTradingDay = false
    while (!cursorIsTradingDay) {
      cursorMs -= DAY_MS
      const cursor = new Date(cursorMs)
      cursorIsTradingDay = isTradingDay(
        cursor.getUTCDay(),
        dateKey(cursor.getUTCFullYear(), cursor.getUTCMonth() + 1, cursor.getUTCDate()),
      )
    }
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
