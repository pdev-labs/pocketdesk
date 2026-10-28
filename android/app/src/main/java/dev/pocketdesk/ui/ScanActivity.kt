package dev.pocketdesk.ui

import android.Manifest
import androidx.activity.ComponentActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.pocketdesk.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Scans the QR code printed by the host and returns the `pocketdesk://` URI.
 *
 * Decoding is on-device (ML Kit bundled model): no frame ever leaves the phone.
 */
class ScanActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private var executor: ExecutorService? = null
    private var scanner: BarcodeScanner? = null
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        previewView = findViewById(R.id.preview)
        findViewById<TextView>(R.id.cancelButton).setOnClickListener { finish() }

        scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
        executor = Executors.newSingleThreadExecutor()

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    val provider = future.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analysis = ImageAnalysis.Builder()
                        // Never queue frames: a lagging decoder must not delay the preview.
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor!!) { imageProxy -> analyze(imageProxy) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (t: Throwable) {
                    Toast.makeText(this, "Camera: ${t.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (media == null || handled) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)

        scanner
            ?.process(image)
            ?.addOnSuccessListener { barcodes ->
                if (handled) return@addOnSuccessListener
                val value = barcodes
                    .mapNotNull { it.rawValue }
                    .firstOrNull { it.startsWith("pocketdesk://") }
                if (value != null) {
                    handled = true
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_URI, value))
                    finish()
                }
            }
            ?.addOnCompleteListener { imageProxy.close() }
    }

    override fun onDestroy() {
        scanner?.close()
        scanner = null
        executor?.shutdown()
        executor = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "uri"
        private const val REQUEST_CAMERA = 7
    }
}
