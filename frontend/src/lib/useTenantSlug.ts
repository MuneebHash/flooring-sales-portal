import { useParams } from 'react-router-dom'
import { DEFAULT_BUSINESS_SLUG } from './tenant'

// Read the active business slug from the current route (the :slug segment).
// Components use this to build slug-prefixed navigation targets, since React
// Router treats absolute paths like "/dashboard" as absolute — they do NOT
// inherit the :slug segment. Falls back to the default slug only if used
// outside a :slug route, which should not happen for in-app screens.
export function useTenantSlug(): string {
  return useParams<{ slug: string }>().slug ?? DEFAULT_BUSINESS_SLUG
}
