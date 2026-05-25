export class ApiError extends Error {
  readonly status: number
  readonly code: string | null
  readonly details: unknown

  constructor(params: {
    status: number
    code: string | null
    message: string
    details?: unknown
  }) {
    super(params.message)
    this.name = 'ApiError'
    this.status = params.status
    this.code = params.code
    this.details = params.details
  }
}
