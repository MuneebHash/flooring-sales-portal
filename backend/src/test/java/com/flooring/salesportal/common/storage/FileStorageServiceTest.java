package com.flooring.salesportal.common.storage;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Phase 15C (Codex P2) — tests for the bounded {@link FileStorageService#readWithLimit} read used by the
 * invoice logo. Proves an over-cap file is rejected via the size-bounded read (no full materialisation),
 * boundary behaviour, the non-positive-cap guard, the missing-file contract, and that the existing
 * path-traversal containment guard still applies. Uses small files + a small cap so "oversized is
 * rejected" is asserted through the bounded path rather than by allocating a multi-MB array.
 */
class FileStorageServiceTest {

    @TempDir
    Path baseDir;

    private FileStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new FileStorageService(baseDir.toString());
    }

    @Test
    void readWithLimit_underCap_returnsBytes() {
        byte[] data = "hello logo".getBytes(StandardCharsets.UTF_8);
        String path = storage.store(data, 1L, 2L, "txt");
        Assertions.assertArrayEquals(data, storage.readWithLimit(path, 1024));
    }

    @Test
    void readWithLimit_atExactCap_isAllowed() {
        byte[] data = new byte[10];
        String path = storage.store(data, 1L, 2L, "bin");
        byte[] read = storage.readWithLimit(path, 10);
        Assertions.assertNotNull(read);
        Assertions.assertEquals(10, read.length);
    }

    @Test
    void readWithLimit_overCap_returnsNull() {
        // 20-byte file with a 10-byte cap: rejected by the bounded read, which never buffers the whole
        // file (the cap is enforced before/while reading, not by measuring an already-loaded array).
        byte[] data = new byte[20];
        String path = storage.store(data, 1L, 2L, "bin");
        Assertions.assertNull(storage.readWithLimit(path, 10));
    }

    @Test
    void readWithLimit_nonPositiveCap_returnsNull() {
        String path = storage.store("x".getBytes(StandardCharsets.UTF_8), 1L, 2L, "txt");
        Assertions.assertNull(storage.readWithLimit(path, 0));
    }

    @Test
    void readWithLimit_missingFile_throwsUncheckedIO() {
        Assertions.assertThrows(UncheckedIOException.class,
                () -> storage.readWithLimit("/uploads/1/orders/2/does-not-exist.png", 1024));
    }

    @Test
    void readWithLimit_pathTraversal_isRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> storage.readWithLimit("/uploads/../../../etc/hosts", 1024));
    }
}
