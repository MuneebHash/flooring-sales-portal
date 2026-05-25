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
