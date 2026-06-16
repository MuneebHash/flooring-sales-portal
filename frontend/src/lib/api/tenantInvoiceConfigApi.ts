import { getActiveSlug } from '../tenant'
import { get } from './client'
import { apiPath } from './paths'
import type { ApiSuccess } from './types'

// Phase 15A — authenticated tenant invoice config (GET /api/v1/{slug}/invoice-config).
// Per-business private invoice-legal / bank / payment fields used to render invoices in a later
// Phase 15 branch (15B/15C). Phase 15A only adds this read wrapper; nothing consumes it yet.
//
// This endpoint IS tenant-scoped and authenticated, so the path goes through
// apiPath(getActiveSlug(), …) — matching every other authenticated wrapper (e.g.
// fetchQuickDescriptions). Do NOT copy the public business-lookup (fetchPublicBusiness) pattern,
// which is unauthenticated and deliberately bypasses apiPath.
//
// Response is the standard ApiSuccess envelope wrapping a single config object:
//   { "data": { "abn": null, …, "invoice_template_key": "standard" } }
// The { data } envelope is unwrapped; callers get the config object directly. The 10 invoice-legal/
// bank/payment/terms fields are nullable (a business may have no invoice config yet);
// invoice_template_key is always a non-null string (backend DB default 'standard'). It is an open
// key, not a locked enum, so it is typed as a plain string.
//
// terms_hard / terms_soft (V13) are the per-flooring-type invoice terms: a SOFT order renders
// terms_soft, a HARD order renders terms_hard. terms_and_conditions is the legacy single-block
// field, retained for compatibility — there is no fallback between the three.
export type TenantInvoiceConfig = {
  abn: string | null
  bank_name: string | null
  bsb: string | null
  account_number: string | null
  account_name: string | null
  terms_and_conditions: string | null
  terms_hard: string | null
  terms_soft: string | null
  logo_path: string | null
  stripe_payment_link_url: string | null
  invoice_template_key: string
}

export function fetchTenantInvoiceConfig(): Promise<TenantInvoiceConfig> {
  return get<ApiSuccess<TenantInvoiceConfig>>(
    apiPath(getActiveSlug(), '/invoice-config'),
  ).then((response) => response.data)
}
