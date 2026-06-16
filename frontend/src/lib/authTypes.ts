// Shared auth domain types, extracted from auth.tsx (Issue #31) so non-React
// modules — API wrappers, mock data — can import them without pulling in the
// AuthProvider/runtime.
//
// This is a LEAF type module: it must not import from auth.tsx or any api
// module, so there is no import cycle. auth.tsx imports these back for its own
// state shapes.

export type User = {
  user_id: number
  first_name: string
  last_name: string
  salesperson_code: string
}

export type Store = {
  store_id: number
  name: string
  store_code: string
  // Phase 15C PR2 — store contact/address exposed by the backend StoreDto (V2 columns),
  // consumed by the invoice document header so the on-screen invoice mirrors the PDF.
  // OPTIONAL so existing construction sites (e.g. data/mockAuth.ts) compile unchanged.
  // snake_case to mirror the backend JSON verbatim (global Jackson SNAKE_CASE; the API
  // client does no casing transform).
  phone?: string
  email?: string | null
  street?: string
  suburb?: string
  state_code?: string
  postcode?: string
}
