import { API_BASE_URL } from './config'
import { ApiError } from './ApiError'
import { DEFAULT_BUSINESS_SLUG } from '../tenant'
import { del, get, post } from './client'
import { apiPath } from './paths'
import type { ApiCollection, ApiSuccess } from './types'

// Phase 11 Chunk 3 B10 — order photos / attachments (D.14 GET list, D.15 POST
// upload, D.16 DELETE, D.17 GET file). Field names are snake_case to mirror the
// backend JSON verbatim (the backend serializes with SNAKE_CASE), matching
// orderNotesApi.ts / orderLinesApi.ts / orderWorkspaceApi.ts.
//
// LAID split (conventions §16 / Chunk 3 F.8): list, upload, and download/preview
// are ALLOWED on a LAID order; only DELETE is blocked (422 ORDER_LOCKED). The
// `locked` flag therefore gates DELETE only in the UI — never list/upload/preview.
// No attachment action returns or recomputes the order_financial_summary.
//
// Upload accepts EXACTLY two multipart fields — `file` + `attachment_kind`
// (= 'PHOTO'); any other part is rejected (400). Every other column
// (order_id / storage_path / stored_file_id / created_at, …) is backend-owned.

// One attachment in the GET list AND the POST data.attachment payload — exactly
// the backend AttachmentRead contract (seven fields). storage_path /
// stored_file_id / order_id are never returned. download_path is the
// backend-built relative URL of the D.17 file-stream endpoint, used verbatim by
// the preview fetch (the frontend never assembles it).
export type OrderAttachment = {
  order_attachment_id: number
  attachment_kind: 'PHOTO'
  file_name: string
  mime_type: string
  file_size: number
  download_path: string
  created_at: string
}

// POST 201 data envelope: the created attachment nested under an `attachment`
// key, wrapped by the standard ApiSuccess `data` envelope with the message
// "Attachment uploaded.".
export type AttachmentUploadResponse = {
  attachment: OrderAttachment
}

// DELETE 200 data envelope: just the id of the removed attachment, wrapped by
// the standard ApiSuccess `data` envelope with the message "Attachment removed.".
export type AttachmentDeleteResponse = {
  deleted_attachment_id: number
}

// Upload accepts only these image MIME types (Chunk 3 D.15). The backend
// enforces them too — this is a fast client-side pre-check, not the authority.
export const PHOTO_ALLOWED_MIME = [
  'image/jpeg',
  'image/png',
  'image/webp',
] as const

// Exactly 10 MB. size > this → backend 400 FILE_TOO_LARGE; size == this is allowed.
export const PHOTO_MAX_BYTES = 10_485_760

// Backend default first-page size for GET /attachments. The client mirrors it so
// the visible list never grows past one server page after a local prepend.
export const ATTACHMENTS_PAGE_SIZE = 20

// GET /api/v1/{slug}/orders/{orderId}/attachments — list attachments, newest
// first (created_at DESC, order_attachment_id DESC). Page 1 / page_size 20 are
// the backend defaults, so no query is sent. Allowed on LAID orders (read-only).
export function fetchOrderAttachments(
  orderId: number,
): Promise<ApiCollection<OrderAttachment>> {
  return get<ApiCollection<OrderAttachment>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/attachments`),
  )
}

// POST /api/v1/{slug}/orders/{orderId}/attachments — upload one photo as
// multipart/form-data. The body carries ONLY `file` + `attachment_kind` ('PHOTO')
// — the backend rejects any other part. The request client passes FormData
// through raw, so the browser sets the multipart Content-Type + boundary; never
// set Content-Type manually here. Allowed on LAID orders.
export function uploadOrderAttachment(
  orderId: number,
  file: File,
): Promise<ApiSuccess<AttachmentUploadResponse>> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('attachment_kind', 'PHOTO')
  return post<ApiSuccess<AttachmentUploadResponse>>(
    apiPath(DEFAULT_BUSINESS_SLUG, `/orders/${orderId}/attachments`),
    formData,
  )
}

// DELETE /api/v1/{slug}/orders/{orderId}/attachments/{attachmentId} — remove one
// attachment. Returns only the deleted id. BLOCKED on LAID orders (422
// ORDER_LOCKED), so the UI gates this behind `!locked`.
export function deleteOrderAttachment(
  orderId: number,
  attachmentId: number,
): Promise<ApiSuccess<AttachmentDeleteResponse>> {
  return del<ApiSuccess<AttachmentDeleteResponse>>(
    apiPath(
      DEFAULT_BUSINESS_SLUG,
      `/orders/${orderId}/attachments/${attachmentId}`,
    ),
  )
}

// GET {download_path} — fetch the raw image bytes for a credentialed preview.
// This canNOT use request<T>: the file endpoint returns RAW BINARY (the stored
// image MIME), while request<T>/parseBody always JSON-parses the body. It is also
// session-protected and cross-origin to the dev server, so a bare
// <img src={download_path}> would not carry the session cookie — hence a
// credentialed fetch → Blob (→ object URL in the component).
//
// `downloadPath` is the backend-provided relative path (starts with /api/v1/…);
// it is used verbatim, only prefixed with the API base. On a non-2xx response the
// body is the standard JSON error envelope, so parse it to preserve the backend
// code/message.
export async function fetchAttachmentBlob(downloadPath: string): Promise<Blob> {
  const base = API_BASE_URL.replace(/\/+$/, '')
  let response: Response
  try {
    response = await fetch(`${base}${downloadPath}`, {
      credentials: 'include',
    })
  } catch (err) {
    throw new ApiError({
      status: 0,
      code: null,
      message: 'Network request failed.',
      details: err,
    })
  }

  if (!response.ok) {
    let code: string | null = null
    let message = 'Could not load photo.'
    try {
      const body: unknown = await response.json()
      const errorObj =
        body && typeof body === 'object' && 'error' in body
          ? (body as { error?: { code?: unknown; message?: unknown } }).error
          : null
      if (errorObj && typeof errorObj === 'object') {
        if (typeof errorObj.code === 'string' && errorObj.code.length > 0) {
          code = errorObj.code
        }
        if (
          typeof errorObj.message === 'string' &&
          errorObj.message.length > 0
        ) {
          message = errorObj.message
        }
      }
    } catch {
      // Non-JSON / empty error body — keep the friendly fallback message.
    }
    throw new ApiError({ status: response.status, code, message })
  }

  return response.blob()
}
