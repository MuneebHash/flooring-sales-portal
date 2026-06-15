import type { Store, User } from '../authTypes'
import { getActiveSlug } from '../tenant'
import { post } from './client'
import { apiPath } from './paths'
import type { ApiSuccess } from './types'

export type LoginRequestBody = {
  salesperson_code: string
  password: string
}

export type LoginResponse = {
  user: User
  stores: Store[]
  active_store_id: number | null
  store_selection_required: boolean
}

export type SelectStoreRequestBody = {
  store_id: number
}

export type SelectStoreResponse = {
  user: User
  active_store: Store
}

export function loginRequest(
  body: LoginRequestBody,
): Promise<ApiSuccess<LoginResponse>> {
  return post<ApiSuccess<LoginResponse>>(
    apiPath(getActiveSlug(), '/auth/login'),
    body,
  )
}

export function selectStoreRequest(
  body: SelectStoreRequestBody,
): Promise<ApiSuccess<SelectStoreResponse>> {
  return post<ApiSuccess<SelectStoreResponse>>(
    apiPath(getActiveSlug(), '/auth/select-store'),
    body,
  )
}

export function logoutRequest(): Promise<ApiSuccess<null>> {
  return post<ApiSuccess<null>>(
    apiPath(getActiveSlug(), '/auth/logout'),
  )
}
