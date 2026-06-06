import { useEffect, useState } from 'react'
import { Modal } from '../ui/Modal'
import { PhotoIcon, XCircleIcon } from '../icons'
import {
  fetchAttachmentBlob,
  type OrderAttachment,
} from '../../lib/api/orderAttachmentsApi'

// Tiny, self-contained copies of the NotesPhotosTab formatters. Kept local so this
// modal shares no state with the tab and the notes/photos tab stays untouched.
const MONTH_NAMES = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
]

function formatTimestamp(iso: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(iso)
  if (!match) return ''
  const [, year, month, day, hours, minutes] = match
  const name = MONTH_NAMES[Number(month) - 1]
  if (!name) return ''
  return `${day} ${name} ${year}, ${hours}:${minutes}`
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

type Props = {
  photo: OrderAttachment
  onClose: () => void
}

// B11 — large in-app photo preview (lightbox). Reuses ui/Modal, so Escape, backdrop
// click, aria-modal and labelledBy come for free; the X button below adds the third
// close affordance.
//
// OBJECT URL OWNERSHIP: this modal fetches and owns a SEPARATE object URL via
// fetchAttachmentBlob + URL.createObjectURL. It deliberately does NOT reuse the
// thumbnail previewUrls[id] URL from NotesPhotosTab, because that thumbnail URL is
// revoked at several points (list reload, delete, cap-drop reconcile, tab/order
// unmount) that can fire while this modal is open and would break a reused <img>.
//
// The parent keys this component by the selected attachment id, so picking a
// different photo remounts it (fresh fetch + fresh cleanup) and closing/unmounting
// runs the cleanup that revokes the URL. The closure-tracked `objectUrl` + `cancelled`
// guard ensure a blob that resolves AFTER close/unmount is revoked immediately and
// never sets state — no leak, no setState-after-unmount.
export function PhotoPreviewModal({ photo, onClose }: Props) {
  const [imageUrl, setImageUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let cancelled = false
    let objectUrl: string | null = null // closure-tracked — authoritative for revoke
    setImageUrl(null)
    setLoading(true)
    setFailed(false)
    fetchAttachmentBlob(photo.download_path)
      .then((blob) => {
        objectUrl = URL.createObjectURL(blob)
        if (cancelled) {
          // Resolved after close/unmount: revoke now, never set state.
          URL.revokeObjectURL(objectUrl)
          objectUrl = null
          return
        }
        setImageUrl(objectUrl)
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
      // Revoke a URL created before this cleanup ran.
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [photo.download_path])

  return (
    <Modal
      open
      onClose={onClose}
      labelledBy="photo-preview-title"
      // Tailwind v4 important modifier is a TRAILING `!` (`max-w-5xl!`), not the v3
      // leading form — it forces width past Modal's own `max-w-md` so the lightbox is
      // genuinely large (64rem). `w-full` on the inner keeps it viewport-bounded, so
      // there is no horizontal overflow on smaller laptop/iPad widths.
      className="max-w-5xl!"
    >
      <div className="p-4 sm:p-5">
        <div className="mb-3 flex items-start justify-between gap-3">
          <h3
            id="photo-preview-title"
            className="truncate text-sm font-semibold tracking-tight text-slate-900"
            title={photo.file_name}
          >
            {photo.file_name}
          </h3>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close preview"
            className="-mr-1 -mt-1 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30"
          >
            <XCircleIcon className="h-5 w-5" />
          </button>
        </div>

        <div className="flex min-h-[200px] items-center justify-center overflow-hidden rounded-lg bg-slate-100">
          {loading ? (
            <p className="px-4 py-16 text-sm text-slate-500">Loading…</p>
          ) : failed ? (
            <div className="flex flex-col items-center gap-1 px-4 py-16 text-center">
              <PhotoIcon className="h-10 w-10 text-slate-400" />
              <span className="text-xs text-slate-500">Preview unavailable</span>
            </div>
          ) : imageUrl ? (
            <img
              src={imageUrl}
              alt={photo.file_name}
              // Fill the (now much wider) modal content width instead of `w-auto`
              // (which kept landscape photos thumbnail-sized). `h-auto` preserves
              // aspect ratio; `max-h-[80vh]` + `object-contain` cap height and prevent
              // cropping/viewport overflow on tall portrait images.
              className="h-auto max-h-[80vh] w-full object-contain"
            />
          ) : null}
        </div>

        <div className="mt-3 text-[11px] font-medium tabular-nums text-slate-500">
          {formatFileSize(photo.file_size)} · {formatTimestamp(photo.created_at)}
        </div>
      </div>
    </Modal>
  )
}
