package dev.mike.couchtour

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders `text` (a pairing code) as a black-on-white QR bitmap. zxing-core alone is enough
 * for this — no camera dependency, unlike the scanning half below.
 */
fun qrCodeBitmap(text: String, sizePx: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap.asImageBitmap()
}

/** The charset D131/`randomPairingCode` draws from — used to recognize a scanned code as one
 *  of ours rather than acting on an arbitrary QR someone points the camera at. */
private val PAIRING_CODE_PATTERN = Regex("^[A-HJ-NP-Z2-9]{8}$")

/** Pulled out of the ML Kit-specific scanning code below so the matching rule itself — what
 *  counts as "one of our pairing codes" — is unit-testable without mocking a `Barcode`. */
fun looksLikePairingCode(scannedText: String?): String? =
    scannedText?.trim()?.uppercase()?.takeIf { PAIRING_CODE_PATTERN.matches(it) }

/**
 * Live camera preview that hands each frame to ML Kit's on-device barcode scanner, popping
 * back with the scanned code (via the previous screen's `SavedStateHandle`, Compose
 * Navigation's standard way to return a result) the first time a frame decodes to something
 * shaped like one of our pairing codes. Pairing codes are already validated server-side
 * (D127) — the regex here just filters out whatever random QR someone's camera crosses before
 * they point it at the right one, not a security boundary.
 */
@Composable
fun ScanScreen(nav: NavHostController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(Modifier.fillMaxSize()) {
        Header("Scan QR code", nav)
        if (!hasPermission) {
            Text(
                "Couch Tour needs camera access to scan a pairing code.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            return@Column
        }

        var scanned by remember { mutableStateOf(false) }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val scanner = BarcodeScanning.getClient()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                        val mediaImage = proxy.image
                        if (mediaImage == null || scanned) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                val code = barcodes.firstNotNullOfOrNull { it.matchingCode() }
                                if (code != null && !scanned) {
                                    scanned = true
                                    nav.previousBackStackEntry?.savedStateHandle?.set("scannedCode", code)
                                    nav.popBackStack()
                                }
                            }
                            .addOnCompleteListener { proxy.close() }
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )
    }
}

private fun Barcode.matchingCode(): String? = looksLikePairingCode(rawValue)
