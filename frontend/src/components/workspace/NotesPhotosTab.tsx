import { useEffect, useRef, useState, type ChangeEvent } from 'react'
import { Button } from '../ui/Button'
import { Textarea } from '../ui/Textarea'
import { ClockIcon, PhotoIcon, PlusIcon, TrashIcon, UploadIcon } from '../icons'
import { ApiError } from '../../lib/api/ApiError'
import {
  addOrderNote,
  fetchOrderNotes,
  type OrderNote,
} from '../../lib/api/orderNotesApi'
import {
  ATTACHMENTS_PAGE_SIZE,
  PHOTO_ALLOWED_MIME,
  PHOTO_MAX_BYTES,
  deleteOrderAttachment,
  fetchAttachmentBlob,
  fetchOrderAttachments,
  uploadOrderAttachment,
  type OrderAttachment,
} from '../../lib/api/orderAttachmentsApi'
import { PhotoPreviewModal } from './PhotoPreviewModal'

type Props = {
  orderId: number
  // `locked` (order is LAID) gates the photo DELETE control ONLY. It must never
  // disable notes (LAID-exempt) or photo list/upload/preview (all allowed on LAID).
  locked: boolean
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

export function NotesPhotosTab({ orderId, locked }: Props) {
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

  // ---------------------------------------------------------------------------
  // Photos / attachments (B10). State is FULLY independent from notes, so a
  // photos failure never breaks notes (and vice versa). LAID rule: list, upload
  // and preview are allowed on a locked order — only DELETE is gated by `locked`.
  // ---------------------------------------------------------------------------
  const [photos, setPhotos] = useState<OrderAttachment[]>([])
  // Real total from the GET pagination (separate from the displayed count: page 1
  // shows at most 20 newest photos, so the server may hold more than are rendered).
  const [photosTotal, setPhotosTotal] = useState(0)
  const [photosLoading, setPhotosLoading] = useState(true)
  const [photosError, setPhotosError] = useState<string | null>(null)
  const [photosReloadToken, setPhotosReloadToken] = useState(0)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  // B11 — large in-app photo preview. Stores the SELECTED attachment id (not the
  // object) so the auto-close effect below can dismiss the modal when that photo
  // leaves the visible list (delete / reload / cap-drop). The modal owns its OWN
  // object URL — it never reuses the thumbnail previewUrls below. See PhotoPreviewModal.
  const [selectedPhotoId, setSelectedPhotoId] = useState<number | null>(null)
  // attachment id → object URL for the credentialed preview, and ids whose
  // preview fetch failed. previewUrlsRef is the AUTHORITATIVE set used for
  // revocation (so unmount cleanup can revoke without depending on render state);
  // previewUrls mirrors it for rendering.
  const [previewUrls, setPreviewUrls] = useState<Record<number, string>>({})
  const [previewErrors, setPreviewErrors] = useState<Record<number, boolean>>({})
  // Refs mirror the preview maps so the loader effect can dedupe WITHOUT taking
  // them as dependencies (depending on them would re-run the effect on every
  // preview result and double-fetch the ones still loading). previewUrlsRef is
  // also the authoritative set revoked on unmount.
  const previewUrlsRef = useRef<Record<number, string>>({})
  const previewErrorsRef = useRef<Record<number, boolean>>({})
  const previewInFlightRef = useRef<Set<number>>(new Set())
  // Bumped on every list (re)load; an async preview resolution from a previous
  // load is discarded when its captured epoch no longer matches.
  const previewEpochRef = useRef(0)
  const fileInputRef = useRef<HTMLInputElement>(null)
  // Bumped when a photos GET starts AND when an upload prepends a photo, so a GET
  // that resolves AFTER an upload completed is discarded rather than overwriting
  // the just-uploaded photo (Codex issue 1) — belt-and-suspenders behind the
  // canUploadPhoto disable.
  const photosFetchEpochRef = useRef(0)

  // Latest photos list for async preview resolvers (the effect closure's `photos`
  // would be stale): a blob that resolves AFTER its photo was deleted must not
  // re-create a URL for a gone attachment.
  const photosRef = useRef<OrderAttachment[]>(photos)
  useEffect(() => {
    photosRef.current = photos
  }, [photos])

  // Revoke EVERY preview object URL on unmount. NotesPhotosTab unmounts on every
  // tab switch, and WorkspaceShell is keyed by orderId (an order switch remounts
  // it), so this one cleanup covers both tab-switch and order-switch leaks.
  useEffect(() => {
    return () => {
      for (const url of Object.values(previewUrlsRef.current)) {
        URL.revokeObjectURL(url)
      }
      previewUrlsRef.current = {}
    }
  }, [])

  // Load page 1 of photos (newest 20) whenever the order changes or a retry is
  // requested. A fresh list invalidates prior previews, so revoke them first.
  // The `cancelled` flag prevents a stale fetch from applying after unmount/reload.
  useEffect(() => {
    let cancelled = false
    // Capture this GET's fetch epoch. An upload that completes while this GET is in
    // flight bumps the epoch (so does a later reload), so a stale GET resolution is
    // DISCARDED instead of overwriting a just-uploaded photo (Codex issue 1). The
    // GET still ends the loading state in `finally` regardless.
    photosFetchEpochRef.current += 1
    const fetchEpoch = photosFetchEpochRef.current
    setPhotosLoading(true)
    setPhotosError(null)
    fetchOrderAttachments(orderId)
      .then((res) => {
        if (cancelled || photosFetchEpochRef.current !== fetchEpoch) return
        // A fresh list invalidates prior previews: bump the epoch (discards any
        // in-flight preview from the previous load), revoke existing object URLs,
        // and reset the preview maps before the new list renders.
        previewEpochRef.current += 1
        for (const url of Object.values(previewUrlsRef.current)) {
          URL.revokeObjectURL(url)
        }
        previewUrlsRef.current = {}
        previewErrorsRef.current = {}
        previewInFlightRef.current = new Set()
        setPreviewUrls({})
        setPreviewErrors({})
        setPhotos(res.data)
        setPhotosTotal(res.pagination.total_items)
      })
      .catch((err: unknown) => {
        if (cancelled || photosFetchEpochRef.current !== fetchEpoch) return
        setPhotosError(
          apiErrorMessage(err, 'Could not load photos. Please try again.'),
        )
      })
      .finally(() => {
        if (cancelled) return
        setPhotosLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [orderId, photosReloadToken])

  // Build a credentialed object-URL preview for each photo that doesn't have one
  // yet. A direct <img src={download_path}> can't be used (the file endpoint is
  // session-protected AND cross-origin to the dev server), so fetch the bytes
  // with the cookie and wrap them in an object URL. Guards skip a resolve after
  // unmount or after the photo was deleted, so no orphan URL is ever created.
  useEffect(() => {
    const epoch = previewEpochRef.current
    photos.forEach((photo) => {
      const id = photo.order_attachment_id
      // Skip photos that already have a preview, already failed, or are mid-fetch.
      // The maps are refs so this effect depends only on `photos`; that also means
      // it does NOT cancel in-flight previews on an upload/delete re-run (existing
      // previews keep loading and apply via the guards below).
      if (
        previewUrlsRef.current[id] ||
        previewErrorsRef.current[id] ||
        previewInFlightRef.current.has(id)
      ) {
        return
      }
      previewInFlightRef.current.add(id)
      fetchAttachmentBlob(photo.download_path)
        .then((blob) => {
          // Discard if unmounted, the list reloaded (epoch changed), or the photo
          // was deleted while its blob loaded — so no orphan URL is ever created.
          if (!mountedRef.current || previewEpochRef.current !== epoch) return
          const stillPresent = photosRef.current.some(
            (p) => p.order_attachment_id === id,
          )
          if (!stillPresent) return
          const url = URL.createObjectURL(blob)
          previewUrlsRef.current[id] = url
          setPreviewUrls((prev) => ({ ...prev, [id]: url }))
        })
        .catch(() => {
          if (!mountedRef.current || previewEpochRef.current !== epoch) return
          previewErrorsRef.current[id] = true
          setPreviewErrors((prev) => ({ ...prev, [id]: true }))
        })
        .finally(() => {
          if (previewEpochRef.current === epoch) {
            previewInFlightRef.current.delete(id)
          }
        })
    })
  }, [photos])

  // Reconcile previews against the visible list: revoke + drop any preview URL
  // whose photo is no longer shown (it fell off the capped page after an upload,
  // or was deleted). Keeps the object-URL set in lockstep with `photos`.
  useEffect(() => {
    const visibleIds = new Set(photos.map((p) => p.order_attachment_id))
    let changed = false
    for (const [idStr, url] of Object.entries(previewUrlsRef.current)) {
      if (!visibleIds.has(Number(idStr))) {
        URL.revokeObjectURL(url)
        delete previewUrlsRef.current[Number(idStr)]
        changed = true
      }
    }
    if (!changed) return
    setPreviewUrls((prev) => {
      const next: Record<number, string> = {}
      for (const [idStr, url] of Object.entries(prev)) {
        if (visibleIds.has(Number(idStr))) next[Number(idStr)] = url
      }
      return next
    })
    setPreviewErrors((prev) => {
      const next: Record<number, boolean> = {}
      for (const [idStr, failed] of Object.entries(prev)) {
        if (visibleIds.has(Number(idStr))) next[Number(idStr)] = failed
      }
      return next
    })
  }, [photos])

  // B11 — auto-close the preview if its photo is no longer in the visible list
  // (deleted, reloaded away, or dropped off the capped page). The delete handler
  // only reloads the list; it does not signal the modal, so this effect is what
  // dismisses a preview whose underlying photo has gone.
  useEffect(() => {
    if (
      selectedPhotoId !== null &&
      !photos.some((p) => p.order_attachment_id === selectedPhotoId)
    ) {
      setSelectedPhotoId(null)
    }
  }, [photos, selectedPhotoId])

  // Upload is disabled while the initial/refresh GET is loading (so an upload can't
  // race the list fetch and then be overwritten by the older GET — Codex issue 1)
  // AND while another upload is in flight. NEVER gated by `locked`: upload is
  // allowed on LAID orders.
  const canUploadPhoto = !photosLoading && !uploading

  function openFilePicker() {
    if (!canUploadPhoto) return
    fileInputRef.current?.click()
  }

  async function handleFileSelected(event: ChangeEvent<HTMLInputElement>) {
    const input = event.target
    const file = input.files?.[0] ?? null
    // Reset immediately so picking the SAME file again still fires onChange.
    input.value = ''
    if (file === null || !canUploadPhoto) return

    setUploadError(null)
    // Client-side pre-validation (single file). The backend still enforces both;
    // this just avoids an obviously-doomed round trip.
    if (!(PHOTO_ALLOWED_MIME as readonly string[]).includes(file.type)) {
      setUploadError('Unsupported file type. Use JPG, PNG or WEBP.')
      return
    }
    if (file.size > PHOTO_MAX_BYTES) {
      setUploadError('That file is too large. The maximum size is 10 MB.')
      return
    }

    setUploading(true)
    try {
      const res = await uploadOrderAttachment(orderId, file)
      if (!mountedRef.current) return
      // Invalidate any photos GET still in flight so its older snapshot cannot
      // overwrite this just-uploaded photo when it resolves (Codex issue 1).
      photosFetchEpochRef.current += 1
      // Prepend the server-confirmed attachment (list is newest-first) and cap to
      // one server page, mirroring notes; any photo that drops off the page is
      // revoked by the reconcile effect above.
      setPhotos((prev) =>
        [res.data.attachment, ...prev].slice(0, ATTACHMENTS_PAGE_SIZE),
      )
      setPhotosTotal((prev) => prev + 1)
      setUploadError(null)
      // Clear a stale list-load error on SUCCESS ONLY: the render checks
      // photosError before photos.length, so a prior failed GET would otherwise
      // keep the error panel up and hide the just-uploaded photo. Not cleared at
      // upload start — a later upload failure must not mask that the list load
      // failed (Codex issue: clear stale photo load errors after successful upload).
      setPhotosError(null)
    } catch (err) {
      if (!mountedRef.current) return
      setUploadError(
        apiErrorMessage(err, 'Could not upload photo. Please try again.'),
      )
    } finally {
      if (mountedRef.current) setUploading(false)
    }
  }

  async function handleDeletePhoto(attachmentId: number) {
    // Delete is blocked on LAID; the control is hidden when locked — this is a
    // belt-and-suspenders guard. One delete at a time.
    if (locked || deletingId !== null) return
    setDeletingId(attachmentId)
    setDeleteError(null)
    try {
      await deleteOrderAttachment(orderId, attachmentId)
      if (!mountedRef.current) return
      setDeleteError(null)
      // Revoke the deleted photo's preview eagerly so the brief pre-reload render
      // shows the placeholder, not a dangling blob URL. (The reload below revokes
      // every remaining preview URL as well.)
      const url = previewUrlsRef.current[attachmentId]
      if (url) {
        URL.revokeObjectURL(url)
        delete previewUrlsRef.current[attachmentId]
      }
      setPreviewUrls((prev) => {
        const next = { ...prev }
        delete next[attachmentId]
        return next
      })
      // B11 — if the deleted photo is the one open in the preview, close the modal
      // NOW (on DELETE success), not only via the reload-driven auto-close effect.
      // That effect fires only once `photos` actually changes; if the follow-up
      // reload below FAILS, `photos` still contains this attachment, so the modal
      // would otherwise stay open for an already-deleted photo (Codex).
      setSelectedPhotoId((current) =>
        current === attachmentId ? null : current,
      )
      // Refetch page 1 from backend truth instead of only filtering locally, so a
      // truncated page backfills (e.g. 21 total / 20 shown → still 20 after a
      // delete) rather than showing a stale 19-of-20 (Codex issue 2). The reload
      // flow revokes ALL remaining preview URLs and its epoch guard discards any
      // late preview fetch, so no deleted-photo preview can be re-added.
      setPhotosReloadToken((token) => token + 1)
    } catch (err) {
      if (!mountedRef.current) return
      setDeleteError(
        apiErrorMessage(err, 'Could not remove photo. Please try again.'),
      )
    } finally {
      if (mountedRef.current) setDeletingId(null)
    }
  }

  // True when the server holds more notes than this page-1 view shows.
  const notesTruncated = totalItems > notes.length
  // True when the server holds more photos than this page-1 view shows.
  const photosTruncated = photosTotal > photos.length
  // The live photo backing the open preview, looked up by id each render so it
  // tracks list updates and becomes null once the photo is gone (the auto-close
  // effect above then clears selectedPhotoId, unmounting the modal).
  const selectedPhoto =
    selectedPhotoId === null
      ? null
      : photos.find((p) => p.order_attachment_id === selectedPhotoId) ?? null

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
            {!photosLoading && photosError === null && (
              <span className="text-[11px] font-medium text-slate-500">
                {photosTotal} {photosTotal === 1 ? 'photo' : 'photos'}
              </span>
            )}
          </div>

          {/* Upload is allowed on LAID orders — never gated by `locked`. Single
              file per upload. */}
          <button
            type="button"
            onClick={openFilePicker}
            disabled={!canUploadPhoto}
            className="flex w-full flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed border-slate-300 bg-white px-4 py-8 text-center transition-colors hover:border-slate-400 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
          >
            <UploadIcon className="w-7 h-7 text-slate-400" />
            <div className="text-sm font-medium text-slate-700">
              {uploading ? 'Uploading…' : 'Click to upload a photo'}
            </div>
            <div className="text-[11px] text-slate-500">
              JPG, PNG or WEBP, up to 10 MB each
            </div>
          </button>
          <input
            ref={fileInputRef}
            type="file"
            accept="image/jpeg,image/png,image/webp"
            className="hidden"
            onChange={handleFileSelected}
          />
          {uploadError !== null && (
            <p className="mt-2 text-xs text-red-600">{uploadError}</p>
          )}

          <div className="mt-4">
            {photosLoading ? (
              <div className="rounded-lg border border-dashed border-slate-300 bg-white px-4 py-10 text-center">
                <p className="text-sm text-slate-500">Loading photos…</p>
              </div>
            ) : photosError !== null ? (
              <div className="rounded-lg border border-red-300 bg-red-50 px-4 py-6 text-center space-y-3">
                <p className="text-sm text-red-800">{photosError}</p>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => setPhotosReloadToken((token) => token + 1)}
                >
                  Try again
                </Button>
              </div>
            ) : photos.length === 0 ? (
              <div className="rounded-lg border border-dashed border-slate-300 bg-white px-4 py-6 text-center">
                <p className="text-sm text-slate-500">No photos yet.</p>
              </div>
            ) : (
              <>
                {deleteError !== null && (
                  <p className="mb-2.5 text-xs text-red-600">{deleteError}</p>
                )}
                {photosTruncated && (
                  <p className="mb-2.5 text-[11px] font-medium text-slate-500">
                    Showing latest {photos.length} of {photosTotal} photos
                  </p>
                )}
                <ul className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
                  {photos.map((photo) => {
                    const id = photo.order_attachment_id
                    const previewUrl = previewUrls[id]
                    const previewFailed = previewErrors[id] === true
                    return (
                      <li
                        key={id}
                        className="relative rounded-lg border border-slate-200 bg-white p-2 shadow-sm"
                      >
                        {/* Preview trigger. A real <button> (not the <li>) so it is
                            focusable and Enter/Space-activatable; it is a SIBLING of
                            the delete button below (never a parent), so a delete click
                            cannot bubble into it. NEVER gated by `locked` — preview is
                            allowed on LAID orders. */}
                        <button
                          type="button"
                          onClick={() => setSelectedPhotoId(id)}
                          aria-label={`View ${photo.file_name}`}
                          className="flex aspect-square w-full items-center justify-center overflow-hidden rounded-md bg-gradient-to-br from-slate-100 to-slate-200 p-0 transition hover:opacity-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/40"
                        >
                          {previewUrl ? (
                            <img
                              src={previewUrl}
                              alt={photo.file_name}
                              className="h-full w-full object-cover"
                            />
                          ) : (
                            <div className="flex flex-col items-center gap-1">
                              <PhotoIcon className="w-8 h-8 text-slate-400" />
                              {previewFailed && (
                                <span className="text-[10px] text-slate-400">
                                  Preview unavailable
                                </span>
                              )}
                            </div>
                          )}
                        </button>
                        {/* Delete is the ONLY photo control gated by LAID: render
                            it only when the order is not locked. */}
                        {!locked && (
                          <button
                            type="button"
                            onClick={() => handleDeletePhoto(id)}
                            disabled={deletingId === id}
                            aria-label={`Remove ${photo.file_name}`}
                            className="absolute right-1.5 top-1.5 inline-flex h-7 w-7 items-center justify-center rounded-md border border-slate-200 bg-white/90 text-slate-500 shadow-sm transition-colors hover:bg-red-50 hover:text-red-600 disabled:cursor-not-allowed disabled:opacity-50"
                          >
                            <TrashIcon className="w-4 h-4" />
                          </button>
                        )}
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
                    )
                  })}
                </ul>
              </>
            )}
          </div>
        </section>
      </div>

      {/* B11 — keyed by the selected id so switching photos remounts the modal
          (fresh blob fetch + cleanup); closing/unmounting revokes its own URL. */}
      {selectedPhoto && (
        <PhotoPreviewModal
          key={selectedPhoto.order_attachment_id}
          photo={selectedPhoto}
          onClose={() => setSelectedPhotoId(null)}
        />
      )}
    </div>
  )
}
