const FALLBACK_BASE_URL = 'http://localhost:8080'

const envBaseUrl = (
  import.meta.env as Record<string, string | undefined>
).VITE_API_BASE_URL

export const API_BASE_URL: string =
  typeof envBaseUrl === 'string' && envBaseUrl.length > 0
    ? envBaseUrl
    : FALLBACK_BASE_URL
