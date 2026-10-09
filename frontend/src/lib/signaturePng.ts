// Phase 16F PR3: bounded PNG export for the PUBLIC quote signature
// (POST /api/v1/public/quotes/{token}/accept).
//
// The backend accepts exactly one image/png part that is non-empty, at most
// 2,097,152 bytes, at most 8192 px per side and at most 4,000,000 px in total
// (contract section 7.2 safe-decode bounds). The shared SignaturePad exports its
// devicePixelRatio-scaled backing store verbatim, and an extreme browser zoom can
// push that past the bounds. This helper takes the pad's own PNG export and:
//   - returns it UNCHANGED (the same bytes) when it is already inside every bound;
//   - otherwise re-encodes the WHOLE image at one uniform reduced scale (aspect
//     ratio kept, nothing cropped, strokes resampled with high-quality smoothing),
//     checking BOTH dimensions and the pixel product, then the byte cap, and
//     shrinking further only if the byte cap still fails.
// It never returns an empty, non-PNG or out-of-bounds Blob: it throws
// SignatureExportError instead, so the caller can show an actionable error rather
// than submit an invalid signature. The in-app invoice flow (D.8) does not use this
// helper, and SignaturePad itself is unchanged.

export const SIGNATURE_PNG_MAX_SIDE = 8192
export const SIGNATURE_PNG_MAX_PIXELS = 4_000_000
export const SIGNATURE_PNG_MAX_BYTES = 2_097_152

export class SignatureExportError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SignatureExportError'
  }
}

type PngSize = { width: number; height: number }

const PNG_MAGIC = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]

// Read the pixel size from the PNG header: the 8-byte signature, then the first
// chunk, which must be a 13-byte IHDR (width and height are its first two
// big-endian 32-bit fields). Returns null for anything that is not a PNG.
async function readPngSize(blob: Blob): Promise<PngSize | null> {
  if (blob.size < 24) return null
  const bytes = new Uint8Array(await blob.slice(0, 24).arrayBuffer())
  for (let i = 0; i < PNG_MAGIC.length; i += 1) {
    if (bytes[i] !== PNG_MAGIC[i]) return null
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  if (view.getUint32(8) !== 13) return null
  const chunkType = String.fromCharCode(bytes[12], bytes[13], bytes[14], bytes[15])
  if (chunkType !== 'IHDR') return null
  const width = view.getUint32(16)
  const height = view.getUint32(20)
  if (width < 1 || height < 1) return null
  return { width, height }
}

function withinPixelBounds(size: PngSize): boolean {
  return (
    size.width <= SIGNATURE_PNG_MAX_SIDE &&
    size.height <= SIGNATURE_PNG_MAX_SIDE &&
    size.width * size.height <= SIGNATURE_PNG_MAX_PIXELS
  )
}

// Target size for a uniform downscale of (width x height): the largest scale
// (never above 1, never above `factor`) that keeps each side within the side cap
// AND the pixel product within the pixel cap. Both dimensions use the SAME scale,
// so the aspect ratio is kept to within one pixel of rounding.
export function boundedSignatureSize(
  width: number,
  height: number,
  factor = 1,
): PngSize {
  const scale = Math.min(
    1,
    factor,
    SIGNATURE_PNG_MAX_SIDE / width,
    SIGNATURE_PNG_MAX_SIDE / height,
    Math.sqrt(SIGNATURE_PNG_MAX_PIXELS / (width * height)),
  )
  let w = Math.max(1, Math.floor(width * scale))
  let h = Math.max(1, Math.floor(height * scale))
  // Floating-point safety net: rounding must never land outside either bound.
  while (
    w > SIGNATURE_PNG_MAX_SIDE ||
    h > SIGNATURE_PNG_MAX_SIDE ||
    w * h > SIGNATURE_PNG_MAX_PIXELS
  ) {
    w = Math.max(1, Math.floor(w * 0.99))
    h = Math.max(1, Math.floor(h * 0.99))
  }
  return { width: w, height: h }
}

type DecodedImage = {
  source: CanvasImageSource
  release: () => void
}

async function decodeImage(blob: Blob): Promise<DecodedImage> {
  if (typeof createImageBitmap === 'function') {
    try {
      const bitmap = await createImageBitmap(blob)
      return { source: bitmap, release: () => bitmap.close() }
    } catch {
      // Fall back to an HTMLImageElement decode below.
    }
  }
  const url = URL.createObjectURL(blob)
  try {
    const image = new Image()
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve()
      image.onerror = () => reject(new Error('The signature image could not be decoded.'))
      image.src = url
    })
    return { source: image, release: () => URL.revokeObjectURL(url) }
  } catch (err) {
    URL.revokeObjectURL(url)
    throw err
  }
}

function canvasToPngBlob(canvas: HTMLCanvasElement): Promise<Blob | null> {
  return new Promise((resolve) => {
    try {
      canvas.toBlob((blob) => resolve(blob), 'image/png')
    } catch {
      resolve(null)
    }
  })
}

// A Blob whose bytes are PNG but whose type is missing would upload as
// application/octet-stream (a 400); label it image/png explicitly.
function asPngBlob(blob: Blob): Blob {
  return blob.type === 'image/png' ? blob : new Blob([blob], { type: 'image/png' })
}

const MAX_REDUCTION_ATTEMPTS = 6
const BYTE_CAP_REDUCTION = 0.7

// Returns a PNG Blob guaranteed to be non-empty, image/png, within 8192 px per
// side, within 4,000,000 px in total and within 2,097,152 bytes; throws
// SignatureExportError otherwise. `raw` is the SignaturePad's own export (null
// when the pad is empty or the canvas export failed).
export async function toBoundedSignaturePng(raw: Blob | null): Promise<Blob> {
  if (!raw || raw.size === 0) {
    throw new SignatureExportError('The signature could not be exported.')
  }
  const size = await readPngSize(raw)
  if (!size) {
    throw new SignatureExportError('The signature export is not a PNG image.')
  }
  const png = asPngBlob(raw)
  if (withinPixelBounds(size) && png.size <= SIGNATURE_PNG_MAX_BYTES) {
    return png
  }

  let decoded: DecodedImage
  try {
    decoded = await decodeImage(png)
  } catch {
    throw new SignatureExportError('The signature could not be resized.')
  }
  try {
    // Pixel bounds first (factor 1 = the largest size that fits). When the pixel
    // bounds already hold and only the byte cap failed, start one step smaller.
    let factor = withinPixelBounds(size) ? BYTE_CAP_REDUCTION : 1
    for (let attempt = 0; attempt < MAX_REDUCTION_ATTEMPTS; attempt += 1) {
      const target = boundedSignatureSize(size.width, size.height, factor)
      const canvas = document.createElement('canvas')
      canvas.width = target.width
      canvas.height = target.height
      const ctx = canvas.getContext('2d')
      if (!ctx) {
        throw new SignatureExportError('The signature could not be resized.')
      }
      ctx.imageSmoothingEnabled = true
      ctx.imageSmoothingQuality = 'high'
      ctx.drawImage(decoded.source, 0, 0, target.width, target.height)
      const out = await canvasToPngBlob(canvas)
      // Release the scratch backing store promptly.
      canvas.width = 0
      canvas.height = 0
      if (out && out.size > 0) {
        const outSize = await readPngSize(out)
        const outPng = asPngBlob(out)
        if (
          outSize &&
          withinPixelBounds(outSize) &&
          outPng.size <= SIGNATURE_PNG_MAX_BYTES
        ) {
          return outPng
        }
      }
      factor *= BYTE_CAP_REDUCTION
    }
    throw new SignatureExportError('The signature could not be made small enough.')
  } finally {
    decoded.release()
  }
}
