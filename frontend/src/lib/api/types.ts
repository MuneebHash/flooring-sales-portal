export type Pagination = {
  page: number
  page_size: number
  total_items: number
  total_pages: number
}

export type ApiSuccess<T> = {
  data: T
  message?: string
}

export type ApiCollection<T> = {
  data: T[]
  pagination: Pagination
}

export type ApiErrorBody = {
  error: {
    code: string
    message: string
    details?: unknown
  }
}

// Public tenant lookup payload (GET /api/v1/public/businesses/{slug}). This is
// the unauthenticated branding whitelist — name plus optional logo/accent. Field
// names are snake_case to match the backend response verbatim; logo_path and
// accent_colour are nullable (a business may have no branding configured yet).
export type PublicBusiness = {
  name: string
  logo_path: string | null
  accent_colour: string | null
}
