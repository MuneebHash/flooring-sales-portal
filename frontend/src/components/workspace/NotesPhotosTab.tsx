import { useEffect, useRef, useState } from 'react'
import { Button } from '../ui/Button'
import { Textarea } from '../ui/Textarea'
import { ClockIcon, PhotoIcon, PlusIcon, UploadIcon } from '../icons'
import { ApiError } from '../../lib/api/ApiError'
import {
  addOrderNote,
  fetchOrderNotes,
  type OrderNote,
} from '../../lib/api/orderNotesApi'
import type { OrderAttachment } from './types'

type Props = {
  orderId: number
}

// Backend default first-page size for GET /notes. The client mirrors it so the
// visible list never grows past one server page after a local prepend (a fresh
// GET would still only return the latest NOTES_PAGE_SIZE notes).
const NOTES_PAGE_SIZE = 20

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

function apiErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError && err.message.length > 0) return err.message
  return fallback
}

export function NotesPhotosTab({ orderId }: Props) {
  const [notes, setNotes] = useState<OrderNote[]>([])
  // Real total from the GET pagination (pagination.total_items), separate from
  // the displayed count: page 1 shows at most 20 newest notes, so the server may
  // hold more than are rendered.
  const [totalItems, setTotalItems] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)
  const [draft, setDraft] = useState('')
  const [adding, setAdding] = useState(false)
  const [addError, setAddError] = useState<string | null>(null)

  const trimmedDraft = draft.trim()
  // Add is allowed only with a non-blank draft, no add already in flight, AND not
  // while the initial list is still loading. The `!loading` guard closes a race:
  // if an add POST resolved before the in-flight GET, the GET's setNotes could
  // overwrite the just-prepended note with its older (pre-add) snapshot. Notes
  // stay allowed on LAID orders — this gates on load/in-flight state only, never
  // on order status.
  const canAdd = trimmedDraft.length > 0 && !adding && !loading

  // Guard a setState after the tab unmounts. The Notes tab is conditionally
  // rendered in OrderWorkspace, so it unmounts on every tab switch — an add that
  // settles afterwards must not setState on an unmounted component (same pattern
  // as ProductsChargesTab / DetailsOfSaleTab).
  const mountedRef = useRef(true)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  // Load page 1 of notes (newest 20) whenever the order changes or a retry is
  // requested. Notes are allowed on LAID orders, so there is no status gate. The
  // `cancelled` flag prevents a stale fetch from applying after unmount/reload.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    fetchOrderNotes(orderId)
      .then((res) => {
        if (cancelled) return
        setNotes(res.data)
        setTotalItems(res.pagination.total_items)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setLoadError(
          apiErrorMessage(err, 'Could not load notes. Please try again.'),
        )
      })
      .finally(() => {
        if (cancelled) return
        setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [orderId, reloadToken])

  async function handleAddNote() {
    if (!canAdd) return // blank draft, an add already in flight, OR list still loading
    setAdding(true)
    setAddError(null)
    try {
      const res = await addOrderNote(orderId, { note_text: trimmedDraft })
      if (!mountedRef.current) return
      // Prepend the server-confirmed note. NOTE: the created note is
      // res.data.note — res.data is the AddNoteResponse wrapper, not the note
      // itself. Prepend (not refetch) is correct because the backend orders
      // notes created_at DESC, order_note_id DESC (newest first), and a freshly
      // created note always has the latest created_at + highest id, so it belongs
      // at index 0. The list is then capped to NOTES_PAGE_SIZE so it mirrors one
      // server page: a fresh GET would only return the latest NOTES_PAGE_SIZE
      // notes, so the oldest visible note drops off and the truncation caption
      // ("Showing latest N of M notes") stays accurate. No page-1 refetch needed.
      setNotes((prev) => [res.data.note, ...prev].slice(0, NOTES_PAGE_SIZE))
      setTotalItems((prev) => prev + 1)
      setDraft('')
    } catch (err) {
      if (!mountedRef.current) return
      setAddError(apiErrorMessage(err, 'Could not add note. Please try again.'))
    } finally {
      if (mountedRef.current) setAdding(false)
    }
  }

  // Photos stay mock/non-functional in B9 (attachments are wired in a later
  // branch). Render an empty local list so the section is visually unchanged.
  const attachmentList: OrderAttachment[] = []

  // True when the server holds more notes than this page-1 view shows.
  const notesTruncated = totalItems > notes.length

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-lg font-semibold text-slate-900 tracking-tight">
          Notes & Photos
        </h2>
        <p className="text-sm text-slate-500 mt-1">
          Capture notes and site photos for this order.
        </p>
      </div>

      <div className="space-y-5">
        <section className="rounded-xl border border-slate-200 bg-slate-50/60 p-5">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-semibold text-slate-900">Notes</h3>
            {!loading && loadError === null && (
              <span className="text-[11px] font-medium text-slate-500">
                {totalItems} {totalItems === 1 ? 'note' : 'notes'}
              </span>
            )}
          </div>

          <div className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
            <Textarea
              rows={3}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              placeholder="Add a note for this order…"
              aria-label="New note"
            />
            {addError !== null && (
              <p className="mt-2 text-xs text-red-600">{addError}</p>
            )}
            <div className="mt-2 flex justify-end">
              <Button
                type="button"
                variant="success"
                size="sm"
                disabled={!canAdd}
                onClick={handleAddNote}
              >
                <PlusIcon className="w-3.5 h-3.5" />
                {adding ? 'Adding…' : 'Add note'}
              </Button>
            </div>
          </div>

          <div className="mt-4">
            {loading ? (
              <div className="rounded-lg border border-dashed border-slate-300 bg-white px-4 py-10 text-center">
                <p className="text-sm text-slate-500">Loading notes…</p>
              </div>
            ) : loadError !== null ? (
              <div className="rounded-lg border border-red-300 bg-red-50 px-4 py-6 text-center space-y-3">
                <p className="text-sm text-red-800">{loadError}</p>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => setReloadToken((token) => token + 1)}
                >
                  Try again
                </Button>
              </div>
            ) : notes.length === 0 ? (
              <div className="rounded-lg border border-dashed border-slate-300 bg-white px-4 py-6 text-center">
                <p className="text-sm text-slate-500">
                  No notes yet. Add the first note above.
                </p>
              </div>
            ) : (
              <>
                {notesTruncated && (
                  <p className="mb-2.5 text-[11px] font-medium text-slate-500">
                    Showing latest {notes.length} of {totalItems} notes
                  </p>
                )}
                <ul className="space-y-2.5">
                  {notes.map((note) => (
                    <li
                      key={note.order_note_id}
                      className="rounded-lg border border-slate-200 bg-white px-4 py-3 shadow-sm"
                    >
                      <p className="text-sm text-slate-700 leading-relaxed whitespace-pre-wrap">
                        {note.note_text}
                      </p>
                      <div className="mt-2 flex items-center gap-1.5 text-[11px] font-medium text-slate-500">
                        <ClockIcon className="w-3 h-3" />
                        <span className="tabular-nums">
                          {formatTimestamp(note.created_at)}
                        </span>
                      </div>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </div>
        </section>

        <section className="rounded-xl border border-slate-200 bg-slate-50/60 p-5">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-semibold text-slate-900">Photos</h3>
            <span className="text-[11px] font-medium text-slate-500">
              {attachmentList.length}{' '}
              {attachmentList.length === 1 ? 'photo' : 'photos'}
            </span>
          </div>

          <div
            role="button"
            tabIndex={0}
            aria-disabled="true"
            className="flex flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed border-slate-300 bg-white px-4 py-8 text-center"
          >
            <UploadIcon className="w-7 h-7 text-slate-400" />
            <div className="text-sm font-medium text-slate-700">
              Click to upload photos
            </div>
            <div className="text-[11px] text-slate-500">
              JPG, PNG or WEBP, up to 10 MB each
            </div>
          </div>

          <div className="mt-4">
            {attachmentList.length === 0 ? (
              <p className="text-sm text-slate-500">No photos yet.</p>
            ) : (
              <ul className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
                {attachmentList.map((photo) => (
                  <li
                    key={photo.order_attachment_id}
                    className="rounded-lg border border-slate-200 bg-white p-2 shadow-sm"
                  >
                    <div className="aspect-square w-full rounded-md bg-gradient-to-br from-slate-100 to-slate-200 flex items-center justify-center">
                      <PhotoIcon className="w-8 h-8 text-slate-400" />
                    </div>
                    <div className="mt-2 px-0.5">
                      <div
                        className="text-xs font-medium text-slate-800 truncate"
                        title={photo.file_name}
                      >
                        {photo.file_name}
                      </div>
                      <div className="mt-0.5 text-[11px] text-slate-500 tabular-nums">
                        {formatFileSize(photo.file_size)} ·{' '}
                        {formatTimestamp(photo.created_at)}
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>
      </div>
    </div>
  )
}
