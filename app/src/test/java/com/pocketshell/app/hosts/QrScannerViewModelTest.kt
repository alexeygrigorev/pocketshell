package com.pocketshell.app.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #129: drive the [QrScannerViewModel] state machine with a
 * fake camera feed of QR strings. Verifies permission handling,
 * single-QR fast path, multi-QR progression, and error / retry.
 *
 * Robolectric is used so `android.util.Base64` (called by
 * [QrChunkCodec]) is on the classpath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QrScannerViewModelTest {

    @Test
    fun initialState_isRequestingPermission() {
        val vm = QrScannerViewModel()
        assertTrue(vm.state.value is QrScannerViewModel.State.RequestingPermission)
    }

    @Test
    fun onPermissionGranted_transitionsToScanning() {
        val vm = QrScannerViewModel()
        vm.onPermissionGranted()
        val state = vm.state.value
        assertTrue("expected Scanning, got $state", state is QrScannerViewModel.State.Scanning)
    }

    @Test
    fun onPermissionDenied_capturesRetryFlag() {
        val vm = QrScannerViewModel()
        vm.onPermissionDenied(canRetry = false)
        val state = vm.state.value
        assertTrue(state is QrScannerViewModel.State.PermissionDenied)
        assertEquals(false, (state as QrScannerViewModel.State.PermissionDenied).canRetry)
    }

    @Test
    fun onPayloadDecoded_singleLegacyPayload_completesImmediately() {
        val vm = QrScannerViewModel()
        vm.onPermissionGranted()
        val payload = """{"type":"pocketshell.ssh-import.v1","version":1}"""
        vm.onPayloadDecoded(payload)
        val state = vm.state.value
        assertTrue("expected Decoded, got $state", state is QrScannerViewModel.State.Decoded)
        assertEquals(payload, (state as QrScannerViewModel.State.Decoded).payload)
    }

    @Test
    fun onPayloadDecoded_multiEnvelope_progresses() {
        val vm = QrScannerViewModel()
        vm.onPermissionGranted()
        val payload = "P".repeat(QrChunkCodec.ChunkSize * 2 + 10)
        val parts = QrChunkCodec.encode(payload, id = "deadbeef")
        assertEquals(3, parts.size)
        // First part — progress.
        vm.onPayloadDecoded(parts[0])
        val mid = vm.state.value as QrScannerViewModel.State.Scanning
        assertEquals(1, mid.scanCount)
        assertEquals(3, mid.scanTotal)
        // Second part — still progressing.
        vm.onPayloadDecoded(parts[1])
        val mid2 = vm.state.value as QrScannerViewModel.State.Scanning
        assertEquals(2, mid2.scanCount)
        // Third part — completes.
        vm.onPayloadDecoded(parts[2])
        val done = vm.state.value
        assertTrue(done is QrScannerViewModel.State.Decoded)
        assertEquals(payload, (done as QrScannerViewModel.State.Decoded).payload)
    }

    @Test
    fun onPayloadDecoded_duplicatePart_doesNotAdvance() {
        val vm = QrScannerViewModel()
        vm.onPermissionGranted()
        val payload = "Q".repeat(QrChunkCodec.ChunkSize * 2 + 10)
        val parts = QrChunkCodec.encode(payload, id = "feed0001")
        vm.onPayloadDecoded(parts[0])
        val first = vm.state.value as QrScannerViewModel.State.Scanning
        vm.onPayloadDecoded(parts[0]) // duplicate
        val second = vm.state.value as QrScannerViewModel.State.Scanning
        assertEquals(first.scanCount, second.scanCount)
    }

    @Test
    fun onPayloadDecoded_corruptEnvelope_transitionsToError() {
        val vm = QrScannerViewModel()
        vm.onPermissionGranted()
        vm.onPayloadDecoded("pocketshell.qr.v1?part=bogus")
        val state = vm.state.value
        assertTrue("expected Error, got $state", state is QrScannerViewModel.State.Error)
    }

    @Test
    fun retry_resetsToRequestingPermission() {
        val vm = QrScannerViewModel()
        vm.onPermissionGranted()
        vm.onPayloadDecoded("pocketshell.qr.v1?part=bogus")
        assertTrue(vm.state.value is QrScannerViewModel.State.Error)
        vm.retry()
        assertTrue(vm.state.value is QrScannerViewModel.State.RequestingPermission)
    }

    @Test
    fun decodedState_ignoresLatePayloads() {
        val vm = QrScannerViewModel()
        vm.onPermissionGranted()
        vm.onPayloadDecoded("""{"type":"pocketshell.ssh-import.v1"}""")
        val first = vm.state.value as QrScannerViewModel.State.Decoded
        vm.onPayloadDecoded("""{"type":"different"}""")
        // The terminal state must not be overwritten by stray frames.
        val later = vm.state.value as QrScannerViewModel.State.Decoded
        assertEquals(first.payload, later.payload)
    }
}
