import { useEffect, useImperativeHandle, useRef } from 'react'
import type { PointerEvent as ReactPointerEvent, Ref } from 'react'

// Phase 13 D.8 — in-app signature capture for invoice acceptance. A plain canvas the customer
// draws on; the parent (InvoiceTab) exports the drawing as an image/png Blob and uploads it as
// the `signature` multipart part. Invoice-specific (not a shared ui/ primitive) by design: the
// signature never appears in Notes & Photos and has no other consumer.
//
// Input handling uses POINTER events (not mouse events) so touch, Apple Pencil/stylus and mouse
// all work — iPad, phone and desktop. `touch-action: none` (Tailwind `touch-none`) stops the page
// scrolling/zooming while signing, and pointer capture keeps a stroke tracking even when the
// pointer leaves the canvas bounds mid-draw.
//
// Strokes are kept in CSS-pixel coordinates in a ref and replayed on resize (the backing store is
// DPR-scaled for crisp lines, and assigning canvas.width/height wipes it). Export uses
// canvas.toBlob('image/png') — the canonical format the backend requires for the signature part.

export type SignaturePadHandle = {
  // Resolves to the drawn signature as an image/png Blob, or null when the pad is empty or the
  // canvas export fails (the caller treats null as "nothing to upload").
  toBlob: () => Promise<Blob | null>
  clear: () => void
}

type Point = { x: number; y: number }

type Props = {
  disabled?: boolean
  // Fired when the pad transitions between empty and inked (true on the first stroke, false on
  // clear) so the parent can gate the Accept button without polling the canvas.
  onInkChange: (hasInk: boolean) => void
  ref?: Ref<SignaturePadHandle>
}

const STROKE_COLOR = '#0f172a' // slate-900, matching the document text
const STROKE_WIDTH = 2.2

export function SignaturePad({ disabled = false, onInkChange, ref }: Props) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  // All completed + in-progress strokes, in CSS-pixel canvas coordinates.
  const strokesRef = useRef<Point[][]>([])
  // The pointerId of the stroke in progress; null when not drawing. Only one
  // pointer draws at a time (a resting palm on an iPad must not scribble).
  const activePointerRef = useRef<number | null>(null)

  function configureContext(ctx: CanvasRenderingContext2D) {
    ctx.lineWidth = STROKE_WIDTH
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    ctx.strokeStyle = STROKE_COLOR
    ctx.fillStyle = STROKE_COLOR
  }

  // Replay every stored stroke onto a cleared canvas. Used after resize (which
  // wipes the backing store) and on clear().
  function redraw(canvas: HTMLCanvasElement) {
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    const dpr = window.devicePixelRatio || 1
    // Clear the FULL backing store under the identity matrix: with the DPR
    // scale applied, clearRect(0, 0, canvas.width, canvas.height) covers
    // width*dpr device pixels — when dpr < 1 (e.g. a zoomed-out display) that
    // is SMALLER than the store, leaving stale ink in the right/bottom strip.
    ctx.save()
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    ctx.restore()
    // Replay strokes in CSS-pixel coordinates under the DPR scale.
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    configureContext(ctx)
    for (const stroke of strokesRef.current) {
      if (stroke.length === 0) continue
      if (stroke.length === 1) {
        drawDot(ctx, stroke[0])
        continue
      }
      ctx.beginPath()
      ctx.moveTo(stroke[0].x, stroke[0].y)
      for (let i = 1; i < stroke.length; i += 1) {
        ctx.lineTo(stroke[i].x, stroke[i].y)
      }
      ctx.stroke()
    }
  }

  // A single tap leaves a visible dot rather than an invisible zero-length line.
  function drawDot(ctx: CanvasRenderingContext2D, point: Point) {
    ctx.beginPath()
    ctx.arc(point.x, point.y, STROKE_WIDTH / 2, 0, Math.PI * 2)
    ctx.fill()
  }

  function drawSegment(from: Point, to: Point) {
    const canvas = canvasRef.current
    const ctx = canvas?.getContext('2d')
    if (!ctx) return
    configureContext(ctx)
    ctx.beginPath()
    ctx.moveTo(from.x, from.y)
    ctx.lineTo(to.x, to.y)
    ctx.stroke()
  }

  // Size the DPR-scaled backing store from the CSS box and keep it in sync on
  // resize (e.g. iPad rotation), replaying the strokes each time. StrictMode-safe:
  // re-running the effect just re-observes and replays from the ref.
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const resize = () => {
      const rect = canvas.getBoundingClientRect()
      const dpr = window.devicePixelRatio || 1
      const width = Math.max(1, Math.round(rect.width * dpr))
      const height = Math.max(1, Math.round(rect.height * dpr))
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width
        canvas.height = height
      }
      redraw(canvas)
    }
    resize()
    const observer = new ResizeObserver(resize)
    observer.observe(canvas)
    return () => observer.disconnect()
    // redraw/strokes live in refs; nothing reactive to depend on.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useImperativeHandle(
    ref,
    () => ({
      toBlob: () => {
        const canvas = canvasRef.current
        if (!canvas || strokesRef.current.length === 0) {
          return Promise.resolve(null)
        }
        return new Promise<Blob | null>((resolve) => {
          canvas.toBlob((blob) => resolve(blob), 'image/png')
        })
      },
      clear: () => {
        strokesRef.current = []
        activePointerRef.current = null
        const canvas = canvasRef.current
        if (canvas) redraw(canvas)
        onInkChange(false)
      },
    }),
    // The handle reads everything through refs; onInkChange is the only prop it
    // closes over and a state setter identity is stable in practice — but track it
    // anyway so a changed callback is never stale.
    [onInkChange],
  )

  function pointFromEvent(e: ReactPointerEvent<HTMLCanvasElement>): Point {
    const rect = e.currentTarget.getBoundingClientRect()
    return { x: e.clientX - rect.left, y: e.clientY - rect.top }
  }

  function handlePointerDown(e: ReactPointerEvent<HTMLCanvasElement>) {
    if (disabled) return
    if (activePointerRef.current !== null) return
    activePointerRef.current = e.pointerId
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // Capture is best-effort (e.g. the pointer was already released).
    }
    const point = pointFromEvent(e)
    strokesRef.current.push([point])
    const canvas = canvasRef.current
    const ctx = canvas?.getContext('2d')
    if (ctx) {
      configureContext(ctx)
      drawDot(ctx, point)
    }
    if (strokesRef.current.length === 1) onInkChange(true)
  }

  function handlePointerMove(e: ReactPointerEvent<HTMLCanvasElement>) {
    if (activePointerRef.current !== e.pointerId) return
    const stroke = strokesRef.current[strokesRef.current.length - 1]
    if (!stroke || stroke.length === 0) return
    const point = pointFromEvent(e)
    const previous = stroke[stroke.length - 1]
    stroke.push(point)
    drawSegment(previous, point)
  }

  function endStroke(e: ReactPointerEvent<HTMLCanvasElement>) {
    if (activePointerRef.current !== e.pointerId) return
    activePointerRef.current = null
    try {
      e.currentTarget.releasePointerCapture(e.pointerId)
    } catch {
      // Already released — nothing to do.
    }
  }

  return (
    <canvas
      ref={canvasRef}
      aria-label="Signature pad — sign here"
      className={`block h-40 w-full touch-none rounded-md ${
        disabled ? 'cursor-not-allowed' : 'cursor-crosshair'
      }`}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={endStroke}
      onPointerCancel={endStroke}
    />
  )
}
