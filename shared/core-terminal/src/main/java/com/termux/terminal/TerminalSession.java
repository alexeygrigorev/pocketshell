package com.termux.terminal;

import android.os.Looper;

/**
 * PocketShell's remote-only terminal session (issue #2566).
 *
 * <p><b>This class is NOT vendored from Termux.</b> It keeps upstream's fully
 * qualified name and its six-method surface so {@link com.termux.view.TerminalView},
 * {@code com.termux.view.textselection.TextSelectionCursorController} and
 * {@link TerminalEmulator} stay unpatched, but the body is ours and is never
 * refreshed from upstream — see {@code VENDORED.md} and {@code PATCHES.md}.
 *
 * <h2>Why it was replaced</h2>
 *
 * Upstream's {@code TerminalSession} exists to spawn a LOCAL pty subprocess
 * through a native library and shuttle bytes between that file descriptor and
 * the emulator across two byte ring buffers, on three dedicated threads, driven
 * by a {@code Handler}. PocketShell's terminal data flow is remote-only: bytes
 * arrive on an SSH PTY channel and keystrokes leave the same way. Every piece
 * of upstream's machinery therefore had to be worked around rather than used —
 * app2's bridge pre-installed the emulator and faked a shell pid by reflection,
 * duplicated a private handler-message constant, polled the input buffer every
 * 8 ms because that ring buffer offers no callback, and the module compiled a
 * stub native library purely so {@code updateSize} would not land in a native
 * call we never wanted.
 *
 * <p>So the shuttle is gone. Remote bytes come in through {@link #append},
 * typed bytes go out through an {@link InputSink}, and there is no handler, no
 * thread, no pid and no native code. The one piece of upstream's plumbing that
 * survives is a small bounded buffer for bytes written while no sink is
 * installed ({@link #PENDING_INPUT_CAPACITY_BYTES}) — that is what carries a
 * keystroke across a reconnect.
 *
 * <h2>Threading</h2>
 *
 * The emulator is single-threaded by upstream contract: everything that touches
 * it runs on the main thread. {@link #append} enforces that with a hard failure
 * rather than trusting its caller, because a parse racing a render is a
 * corrupted grid with no exception to point at. {@link #write} may be called
 * from anywhere (the vendored view calls it from input dispatch) — it only
 * hands bytes to the sink, which is expected to be non-blocking, or parks them
 * in a small bounded buffer while no sink is installed.
 */
public final class TerminalSession extends TerminalOutput {

    /**
     * Everything the user types, and every query reply the emulator emits, in
     * order.
     *
     * <p>Both directions of "the terminal is answering something" land here:
     * a keystroke the vendored view encoded, and the emulator's own replies to
     * {@code CSI 6 n} (cursor position), {@code CSI c} (device attributes) and
     * {@code OSC 11 ?} (background colour). To the remote they are the same
     * stream, which is why there is one sink and not two.
     *
     * <p>Implementations must not block: this is called on the main thread from
     * the view's input dispatch and from inside the emulator's parser.
     */
    public interface InputSink {
        void onInput(byte[] data, int offset, int count);
    }

    /**
     * How many bytes written with no {@link InputSink} installed are held for
     * the next one.
     *
     * <p>4 KB is the size of the terminal-to-process ring buffer upstream's
     * session used to carry exactly these bytes, and that buffer turned out to
     * be load-bearing: between a dropped channel and the reconnect ladder's
     * next successful attach — seconds, since it spans a full SSH dial — the
     * user is still typing at the "Reconnecting" banner, and those keystrokes
     * have to run when the session comes back (journey J06).
     *
     * <p>Past the bound bytes are dropped rather than blocking the caller.
     * Upstream's queue blocked the writing thread when it filled, which here
     * would be the main thread parked inside a keystroke; losing a burst nobody
     * can see the effect of is the better failure.
     */
    public static final int PENDING_INPUT_CAPACITY_BYTES = 4096;

    /** Buffer used to translate a code point into UTF-8 before writing it out. */
    private final byte[] mUtf8InputBuffer = new byte[5];

    private final TerminalEmulator mEmulator;

    /** Callback which gets notified when the screen, the title or the palette changes. */
    private TerminalSessionClient mClient;

    /**
     * Guards the sink and the pending buffer as ONE unit.
     *
     * <p>They are a single piece of state — "where does the next byte go?" —
     * and the answer must not change between the check and the write, or a
     * keystroke racing an attach would be delivered twice or not at all. The
     * sink is invoked while the lock is held, so bytes reach it in the order
     * they were written; sinks are contractually non-blocking (the bridge
     * offers to an unbounded channel), so holding it costs nothing.
     */
    private final Object mInputLock = new Object();

    /**
     * Where typed bytes go. Null means nothing is attached and bytes are held
     * in {@link #mPendingInput} for the next sink.
     */
    private InputSink mInputSink;

    /**
     * Bytes written while {@link #mInputSink} was null, oldest first, flushed
     * by the next {@link #setInputSink}.
     */
    private final byte[] mPendingInput = new byte[PENDING_INPUT_CAPACITY_BYTES];

    private int mPendingInputSize;

    /**
     * Builds the session AND its emulator, at the given geometry.
     *
     * <p>The emulator is final and built here rather than lazily on the first
     * {@link #updateSize}: upstream deferred it because it could not spawn a pty
     * before it knew the window size, and that deferral is exactly what made
     * "is there an emulator yet?" a state every caller had to reason about.
     * There is no pty to spawn here, so there is no reason for the null window.
     *
     * @param columns        initial width in character cells.
     * @param rows           initial height in character cells.
     * @param cellWidthPx    cell width in pixels; reported to the remote by the
     *                       {@code CSI 14t}/{@code CSI 16t} pixel-size queries.
     * @param cellHeightPx   cell height in pixels, likewise.
     * @param transcriptRows scrollback depth.
     * @param client         the callback target; swap it with
     *                       {@link #updateTerminalSessionClient}.
     */
    public TerminalSession(
            int columns,
            int rows,
            int cellWidthPx,
            int cellHeightPx,
            int transcriptRows,
            TerminalSessionClient client) {
        mClient = client;
        mEmulator = new TerminalEmulator(
                this, columns, rows, cellWidthPx, cellHeightPx, transcriptRows, client);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /**
     * Swaps the callback target for BOTH this session and its emulator.
     *
     * <p>Both halves matter: the session reports title/clipboard/bell/palette
     * changes, while the emulator reports cursor style and logging through its
     * own copy of the same reference. Updating one and not the other leaves a
     * detached view being called back.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        mEmulator.updateTerminalSessionClient(client);
    }

    /**
     * Installs (or, with null, removes) the destination for typed bytes,
     * handing it first everything that was written while nobody was listening.
     *
     * <p>The flush is what makes typing at a reconnecting terminal work: the
     * bridge that owned the dead channel cleared the sink, the user kept
     * typing, and the bridge that attaches next installs its sink and receives
     * those bytes, in order, before anything typed afterwards. They are handed
     * over exactly once — the flush empties the pending buffer — and nothing
     * older than the last hand-off is ever replayed, because a sink is only
     * installed on an attach.
     *
     * <p>Ownership is still single and hand-off is still stop-then-start: the
     * bridge that stops clears the sink, the bridge that starts installs its
     * own. Only the gap between the two now holds bytes instead of discarding
     * them, bounded by {@link #PENDING_INPUT_CAPACITY_BYTES}.
     */
    public void setInputSink(InputSink sink) {
        synchronized (mInputLock) {
            if (sink != null && mPendingInputSize > 0) {
                // Copied out and the buffer emptied BEFORE the callout, so a
                // sink that writes back from inside onInput appends after the
                // flush instead of overwriting the bytes being delivered.
                byte[] pending = new byte[mPendingInputSize];
                System.arraycopy(mPendingInput, 0, pending, 0, mPendingInputSize);
                mPendingInputSize = 0;
                sink.onInput(pending, 0, pending.length);
            }
            mInputSink = sink;
        }
    }

    /** Reflows the emulator to a new size. There is no local pty to inform. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
    }

    /** The terminal title as set through escape sequences, or null if none set. */
    public String getTitle() {
        return mEmulator.getTitle();
    }

    /**
     * Parses remote bytes into the grid and reports the change, on the main
     * thread.
     *
     * <p>Called by app2's {@code TerminalPtyBridge} once per output slice. The
     * main-thread requirement is upstream's own (the emulator has no locking
     * and the renderer reads the same buffers on the main thread), and it is
     * asserted rather than assumed because the failure it prevents — a parse
     * racing a render — shows up as a garbled grid days later, not as a stack
     * trace here.
     *
     * @throws IllegalStateException if called off the main thread.
     */
    public void append(byte[] data, int offset, int count) {
        if (!Looper.getMainLooper().isCurrentThread()) {
            throw new IllegalStateException(
                    "TerminalSession.append must run on the main thread (the emulator is "
                            + "single-threaded by upstream contract); was on "
                            + Thread.currentThread().getName());
        }
        if (count <= 0) return;
        if (offset == 0) {
            mEmulator.append(data, count);
        } else {
            // TerminalEmulator.append has no offset parameter, so a mid-array
            // slice has to be copied. The bridge writes at offset 0, so this
            // branch is for callers that hold one buffer and walk it.
            byte[] slice = new byte[count];
            System.arraycopy(data, offset, slice, 0, count);
            mEmulator.append(slice, count);
        }
        mClient.onTextChanged(this);
    }

    /**
     * Send data to the remote: typed bytes and the emulator's own query replies.
     *
     * <p>With no sink installed the bytes are held for the next one (see
     * {@link #setInputSink}), up to {@link #PENDING_INPUT_CAPACITY_BYTES};
     * past that they are dropped. This never blocks and never throws — it is
     * called from the vendored view's input dispatch and from inside the
     * emulator's parser, neither of which can handle either.
     */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (count <= 0) return;
        synchronized (mInputLock) {
            InputSink sink = mInputSink;
            if (sink != null) {
                sink.onInput(data, offset, count);
                return;
            }
            int room = PENDING_INPUT_CAPACITY_BYTES - mPendingInputSize;
            if (room <= 0) return;
            int held = Math.min(room, count);
            System.arraycopy(data, offset, mPendingInput, mPendingInputSize, held);
            mPendingInputSize += held;
        }
    }

    /**
     * Write the Unicode code point to the terminal encoded in UTF-8.
     *
     * <p>Body carried over verbatim from upstream Termux — it is the encoder
     * the vendored {@code TerminalView} calls for every printable key.
     */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

}
