import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { StoryPanel } from './story-panel'

const getStory = vi.fn()
vi.mock('@/lib/api', () => ({
  getStory: (ticker: string) => getStory(ticker),
}))

describe('StoryPanel', () => {
  it('renders a skeleton while the fetch is in flight', () => {
    getStory.mockImplementation(() => new Promise(() => {}))

    const { container } = render(<StoryPanel symbol="X" enabled />)

    expect(container.querySelector('[data-slot="skeleton"]')).toBeInTheDocument()
    expect(screen.queryByText(/could not load story/i)).not.toBeInTheDocument()
  })

  it('renders an error message when the fetch fails', async () => {
    getStory.mockRejectedValue(new Error('boom'))

    render(<StoryPanel symbol="X" enabled />)

    expect(await screen.findByText('Could not load story.')).toBeInTheDocument()
  })

  it('renders the story, source badge and formatted timestamp when found', async () => {
    getStory.mockResolvedValue({
      ticker: 'X',
      story: 'X is up 2.50% over the past 5 sessions.',
      generatedAt: '2026-07-14T12:00:00Z',
      source: 'RULE_BASED',
      inputs: { days: 5, insightCount: 0 },
      found: true,
    })

    render(<StoryPanel symbol="X" enabled />)

    expect(await screen.findByText('X is up 2.50% over the past 5 sessions.')).toBeInTheDocument()
    expect(screen.getByText('RULE_BASED')).toBeInTheDocument()
    expect(screen.queryByText(/2026-07-14T12:00:00Z/)).not.toBeInTheDocument()
    expect(screen.getByText(/14 Jul 2026/)).toBeInTheDocument()
  })

  it('renders the fallback story text in the muted empty-state style when not found', async () => {
    getStory.mockResolvedValue({
      ticker: 'X',
      story: 'Not enough history yet for X; the story builds as market sessions accumulate.',
      generatedAt: '2026-07-14T12:00:00Z',
      source: 'RULE_BASED',
      inputs: { days: 0, insightCount: 0 },
      found: false,
    })

    render(<StoryPanel symbol="X" enabled />)

    const message = await screen.findByText(
      'Not enough history yet for X; the story builds as market sessions accumulate.',
    )
    expect(message).toHaveClass('text-muted-foreground')
    expect(screen.queryByText('RULE_BASED')).not.toBeInTheDocument()
  })

  it('does not fetch when disabled', () => {
    getStory.mockClear()
    render(<StoryPanel symbol="X" enabled={false} />)
    expect(getStory).not.toHaveBeenCalled()
  })
})
