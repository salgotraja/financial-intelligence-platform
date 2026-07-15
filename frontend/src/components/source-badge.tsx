import { ReactNode } from 'react'
import { Badge } from '@/components/ui/badge'

/** Shared small muted outline badge used for source/provenance and group labels across the app. */
export const SourceBadge = ({ children }: { children: ReactNode }) => (
  <Badge variant="outline" className="border-border text-[10px] text-muted-foreground">
    {children}
  </Badge>
)
