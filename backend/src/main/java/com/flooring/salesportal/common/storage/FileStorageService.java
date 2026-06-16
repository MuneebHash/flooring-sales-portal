package com.flooring.salesportal.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Local-disk file storage for order attachments (Chunk 3 Branch 5). Files are written under a
 * configurable base directory ({@code app.storage.base-dir}); the value persisted on
 * {@code stored_file.storage_path} is a STABLE virtual path ({@code /uploads/{businessId}/orders/
 * {orderId}/{uuid}.{ext}}) that is resolved against the base dir on read/delete. This keeps the
 * stored path portable across environments and lets storage move to S3 later (Chunk 3 D.17 note)
 * without rewriting persisted paths.
 *
 * <p>The on-disk filename is always a server-generated UUID + a MIME-derived extension — the client
 * filename is never trusted for the disk path (a safe display name is kept separately on
 * {@code stored_file.file_name}). {@code storage_path} is server-internal and is never exposed in any
 * API response.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /** All persisted storage paths live under this virtual root segment. */
    private static final String VIRTUAL_ROOT = "/uploads/";

    private final Path baseDir;

    public FileStorageService(@Value("${app.storage.base-dir}") String baseDir) {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    /**
     * Write {@code bytes} to a freshly generated path and return the virtual {@code storage_path} to
     * persist. Creates parent directories as needed. Throws {@link UncheckedIOException} if the write
     * fails (the caller treats this as "do not insert DB rows").
     */
    public String store(byte[] bytes, long businessId, long orderId, String extension) {
        String storagePath = VIRTUAL_ROOT + businessId + "/orders/" + orderId + "/"
                + UUID.randomUUID() + "." + extension;
        Path target = resolve(storagePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store attachment file", e);
        }
        return storagePath;
    }

    /**
     * Read the bytes for a persisted {@code storage_path}. Throws {@link UncheckedIOException} when the
     * file is missing on disk — Chunk 3 D.17 maps this to a 500 (standard JSON error wrapper).
     */
    public byte[] read(String storagePath) {
        Path source = resolve(storagePath);
        try {
            return Files.readAllBytes(source);
        } catch (IOException e) {
            throw new UncheckedIOException("Attachment file is not available", e);
        }
    }

    /**
     * Read a persisted {@code storage_path} but never materialise more than {@code maxBytes}. Reads at
     * most {@code maxBytes + 1} bytes from the stream: if the file is larger than {@code maxBytes} it is
     * REJECTED with {@code null} WITHOUT buffering the whole file — so an oversized (or maliciously huge)
     * file cannot OOM the JVM at read time (the flaw {@link #read} has via {@code Files.readAllBytes}).
     * Returns {@code null} for {@code maxBytes <= 0} or an over-limit file; throws
     * {@link UncheckedIOException} when the file is missing/unreadable (same as {@link #read}). The
     * path-traversal containment guard in {@link #resolve} still applies. Phase 15C: used for the invoice
     * logo so logo size is bounded before decode.
     */
    public byte[] readWithLimit(String storagePath, long maxBytes) {
        if (maxBytes <= 0) {
            return null;
        }
        Path source = resolve(storagePath);
        // Read up to maxBytes+1 (capped at Integer.MAX_VALUE) so we can detect "larger than the cap"
        // without ever loading the whole oversized file into memory.
        int readCap = (int) Math.min(maxBytes + 1, Integer.MAX_VALUE);
        try (InputStream in = Files.newInputStream(source)) {
            byte[] data = in.readNBytes(readCap);
            if (data.length > maxBytes) {
                return null;
            }
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException("Attachment file is not available", e);
        }
    }

    /**
     * Best-effort delete of the underlying file. Never throws — a failure here must not roll back the
     * surrounding DB transaction (Chunk 3 D.16: "best-effort delete the underlying disk file").
     */
    public void deleteQuietly(String storagePath) {
        try {
            Files.deleteIfExists(resolve(storagePath));
        } catch (IOException | RuntimeException e) {
            log.warn("Best-effort delete failed for storage_path={}", storagePath, e);
        }
    }

    // Resolve a server-generated virtual path to a real path under baseDir. The path is always
    // server-built (never client input), but normalize + containment check is defence-in-depth
    // against any path-traversal regression.
    private Path resolve(String storagePath) {
        String relative = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        Path target = baseDir.resolve(relative).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("Resolved storage path escapes the base directory");
        }
        return target;
    }
}
