package com.pocketshell.app.composer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Issue #731: a test-only {@link ContentProvider} (registered in the
 * androidTest manifest) that serves a single, deterministic composer
 * attachment in a way that forces {@code PromptAttachmentStager} down the REAL
 * {@code uploadFile} (unknown-size) branch — the #581 data-loss path under
 * guard:
 *
 * <ul>
 *   <li>{@link #query} returns ONLY the display name (taken from the URI's
 *       {@code name} query param) and deliberately OMITS
 *       {@code OpenableColumns.SIZE}, so {@code PromptAttachmentStager.describe}
 *       sees a null size and the stage drains to a temp file and calls
 *       {@code SshSession.uploadFile} (not the known-size {@code uploadStream}
 *       path).</li>
 *   <li>{@link #openFile} streams a deterministic {@value #PAYLOAD_SIZE}-byte
 *       payload (every byte value, {@code i % 256}) that the provider writes to
 *       its OWN cache on demand. The test reproduces the exact same bytes
 *       independently and asserts the host received THEM.</li>
 * </ul>
 *
 * <p>Why self-contained / parameterless payload: the provider is instantiated
 * in the TEST APK's own process+UID (different from the target app process the
 * stager runs in), so static state set by the test method in the app process
 * is NOT visible here. The payload is therefore generated deterministically by
 * a shared formula ({@link #PAYLOAD_SIZE} bytes, {@code (byte)(i % 256)}) that
 * both sides compute independently — no cross-process handoff.
 *
 * <p>Written in <strong>Java</strong> on purpose: a provider in the androidTest
 * manifest runs in the test APK process, which does not bundle the Kotlin
 * stdlib (a Kotlin provider crashes with
 * {@code NoClassDefFoundError: kotlin/jvm/internal/Intrinsics}). Java keeps it
 * dependency-free.
 */
public class Issue731AttachmentProvider extends ContentProvider {

    /** The deterministic payload size in bytes. The test must mirror this. */
    public static final int PAYLOAD_SIZE = 1024;

    /** Default display name when the URI carries no {@code name} query param. */
    private static final String DEFAULT_NAME = "issue731-attachment.bin";

    /** Build the deterministic payload bytes ({@code i % 256}); test mirrors this. */
    public static byte[] payloadBytes() {
        byte[] bytes = new byte[PAYLOAD_SIZE];
        for (int i = 0; i < PAYLOAD_SIZE; i++) {
            bytes[i] = (byte) (i % 256);
        }
        return bytes;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        // Display name only — crucially NO size column, so the stager's
        // describe() yields size == null and takes the uploadFile branch.
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        String name = uri.getQueryParameter("name");
        if (name == null || name.isEmpty()) {
            name = DEFAULT_NAME;
        }
        cursor.addRow(new Object[]{name});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File cacheDir = getContext().getCacheDir();
        File file = new File(cacheDir, "issue731-payload.bin");
        try {
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(payloadBytes());
            } finally {
                out.close();
            }
        } catch (IOException e) {
            throw new FileNotFoundException("could not write issue731 payload: " + e.getMessage());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
