import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MoodGauge } from './mood-gauge'
import type { WatchlistMood } from '@/lib/mood'

const mood: WatchlistMood = {
  score: 72,
  bucket: 'Constructive',
  read: 'More names rising than falling. A quietly positive day.',
  breadth: 80,
  signalScore: 65,
  momentum: 60,
  up: 4,
  down: 1,
  avgChange: 1.8,
}

describe('MoodGauge', () => {
  it('renders the score, bucket, read line, and breakdown', () => {
    render(<MoodGauge mood={mood} />)
    expect(screen.getByText('72')).toBeInTheDocument()
    expect(screen.getByText('Constructive')).toBeInTheDocument()
    expect(screen.getByText(/quietly positive/i)).toBeInTheDocument()
    expect(screen.getByText('4 up / 1 down')).toBeInTheDocument()
    expect(screen.getByText('+1.80% avg')).toBeInTheDocument()
  })

  it('renders a placeholder when mood is null', () => {
    render(<MoodGauge mood={null} />)
    expect(
      screen.getByText(/building your watchlist mood/i),
    ).toBeInTheDocument()
  })
})
