import { API_BASE_URL } from './config'
import { ApiError } from './ApiError'

export type QueryParamValue = string | number | boolean | null | undefined
export type QueryParams = Record<string, QueryParamValue>

export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'

export type RequestOptions = {
  method?: HttpMethod
  body?: unknown
  query?: QueryParams
  headers?: Record<string, string>
}

function buildUrl(path: string, query?: QueryParams): string {
  const base = API_BASE_URL.replace(/\/+$/, '')
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const url = `${base}${normalizedPath}`
  if (!query) return url
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined) continue
    params.append(key, String(value))
  }
  const queryString = params.toString()
  return queryString.length > 0 ? `${url}?${queryString}` : url
}

async function parseBody(response: Response): Promise<unknown> {
  if (response.status === 204) return null
  const text = await response.text()
  if (text.length === 0) return null
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function toApiError(
  status: number,
  body: unknown,
  fallbackMessage: string,
): ApiError {
  const maybeError =
    body && typeof body === 'object' && 'error' in body
      ? (body as { error: unknown }).error
      : null
  const errorObj =
    maybeError && typeof maybeError === 'object'
      ? (maybeError as {
          code?: unknown
          message?: unknown
          details?: unknown
        })
      : null

  if (errorObj) {
    const code =
      typeof errorObj.code === 'string' && errorObj.code.length > 0
        ? errorObj.code
        : null
    const message =
      typeof errorObj.message === 'string' && errorObj.message.length > 0
        ? errorObj.message
        : fallbackMessage
    return new ApiError({
      status,
      code,
      message,
      details: errorObj.details,
    })
  }

  return new ApiError({
    status,
    code: null,
    message: fallbackMessage,
    details: undefined,
  })
}

export async function request<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const { method = 'GET', body, query, headers } = options
  const url = buildUrl(path, query)
  const hasBody = body !== undefined && body !== null
  const finalHeaders = new Headers(headers)
  let requestBody: BodyInit | undefined
  if (hasBody) {
    if (!finalHeaders.has('content-type')) {
      finalHeaders.set('Content-Type', 'application/json')
    }
    requestBody = typeof body === 'string' ? body : JSON.stringify(body)
  }

  let response: Response
  try {
    response = await fetch(url, {
      method,
      credentials: 'include',
      headers: finalHeaders,
      body: requestBody,
    })
  } catch (err) {
    throw new ApiError({
      status: 0,
      code: null,
      message: 'Network request failed.',
      details: err,
    })
  }

  const parsed = await parseBody(response)
  if (!response.ok) {
    throw toApiError(
      response.status,
      parsed,
      response.statusText || 'Request failed.',
    )
  }
  return parsed as T
}

export function get<T>(
  path: string,
  options: Omit<RequestOptions, 'method' | 'body'> = {},
): Promise<T> {
  return request<T>(path, { ...options, method: 'GET' })
}

export function post<T>(
  path: string,
  body?: unknown,
  options: Omit<RequestOptions, 'method' | 'body'> = {},
): Promise<T> {
  return request<T>(path, { ...options, method: 'POST', body })
}

export function patch<T>(
  path: string,
  body?: unknown,
  options: Omit<RequestOptions, 'method' | 'body'> = {},
): Promise<T> {
  return request<T>(path, { ...options, method: 'PATCH', body })
}

export function put<T>(
  path: string,
  body?: unknown,
  options: Omit<RequestOptions, 'method' | 'body'> = {},
): Promise<T> {
  return request<T>(path, { ...options, method: 'PUT', body })
}

export function del<T>(
  path: string,
  options: Omit<RequestOptions, 'method' | 'body'> = {},
): Promise<T> {
  return request<T>(path, { ...options, method: 'DELETE' })
}
