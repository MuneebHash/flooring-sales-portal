import { useState } from 'react'
import { Button } from '../ui/Button'
import { Textarea } from '../ui/Textarea'
import { ClockIcon, PhotoIcon, PlusIcon, UploadIcon } from '../icons'
import type { OrderAttachment, OrderNote } from './types'

type Props = {
  notes?: OrderNote[] | null
  attachments?: OrderAttachment[] | null
}

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

function nowIsoLocal(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

export function NotesPhotosTab({ notes, attachments }: Props) {
  const [noteList, setNoteList] = useState<OrderNote[]>(notes ?? [])
  const [draft, setDraft] = useState('')
  const trimmedDraft = draft.trim()
  const canAdd = trimmedDraft.length > 0

  function handleAddNote() {
    if (!canAdd) return
    const next: OrderNote = {
      order_note_id: Date.now(),
      note_text: trimmedDraft,
      created_at: nowIsoLocal(),
    }
    setNoteList((prev) => [next, ...prev])
    setDraft('')
  }

  const attachmentList = attachments ?? []

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
            <span className="text-[11px] font-medium text-slate-500">
              {noteList.length} {noteList.length === 1 ? 'note' : 'notes'}
            </span>
          </div>

          <div className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
            <Textarea
              rows={3}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              placeholder="Add a note for this order…"
              aria-label="New note"
            />
            <div className="mt-2 flex justify-end">
              <Button
                type="button"
                variant="success"
                size="sm"
                disabled={!canAdd}
                onClick={handleAddNote}
              >
                <PlusIcon className="w-3.5 h-3.5" />
                Add note
              </Button>
            </div>
          </div>

          <div className="mt-4">
            {noteList.length === 0 ? (
              <div className="rounded-lg border border-dashed border-slate-300 bg-white px-4 py-6 text-center">
                <p className="text-sm text-slate-500">
                  No notes yet. Add the first note above.
                </p>
              </div>
            ) : (
              <ul className="space-y-2.5">
                {noteList.map((note) => (
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
