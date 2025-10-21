package com.comvisb.boardtts

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.comvisb.boardtts.Constants.LABELS_PATH
import com.comvisb.boardtts.Constants.MODEL_PATH
import com.comvisb.boardtts.databinding.ActivityMainBinding
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : AppCompatActivity(), Detector.DetectorListener, View.OnClickListener {

    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector

    private val mlKitRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var tts: TextToSpeech
    private var isProcessing = false

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        detector.setup()
        setupTTS()

        binding.scanButton.setOnClickListener(this)
        binding.scanButton.contentDescription = "Scan and Read Text Button. Tap to take a picture and read text aloud."

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun setupTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS Language is not supported.")
                    speakText("Text-to-Speech initialization failed due to language error.")
                } else {
                    speakText("Text reader ready. Tap scan button to begin.")
                }
            } else {
                Log.e(TAG, "TTS Initialization failed.")
            }
        }
    }

    private fun speakText(text: String) {
        if (tts.isSpeaking) tts.stop()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "text_reader_output")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val rotation = binding.viewFinder.display.rotation
        val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageCapture = ImageCapture.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    override fun onClick(v: View?) {
        if (v?.id == binding.scanButton.id && !isProcessing) {
            captureImage()
        }
    }

    private fun captureImage() {
        imageCapture ?: return
        isProcessing = true
        binding.scanButton.isEnabled = false
        speakText("Capturing image and scanning for text. Please wait.")

        imageCapture?.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                runOnUiThread {
                    speakText("Scan failed. Camera error.")
                    isProcessing = false
                    binding.scanButton.isEnabled = true
                }
            }

            @SuppressLint("UnsafeOptInUsageError")
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()
                val bitmap: Bitmap? = when (imageProxy.format) {
                    android.graphics.ImageFormat.JPEG -> {
                        val buffer = imageProxy.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    android.graphics.ImageFormat.YUV_420_888 -> {
                        imageProxy.image?.let { ImageConverter.yuvToBitmap(it) }
                    }
                    else -> {
                        Log.e(TAG, "Image format not supported: ${imageProxy.format}")
                        null
                    }
                }

                imageProxy.close()

                if (bitmap == null) {
                    Log.e(TAG, "Failed to convert ImageProxy to Bitmap.")
                    runOnUiThread {
                        speakText("Scan failed. Image format error.")
                        isProcessing = false
                        binding.scanButton.isEnabled = true
                    }
                    return
                }

                val matrix = Matrix().apply {
                    postRotate(rotationDegrees)
                    if (isFrontCamera) {
                        postScale(-1f, 1f, bitmap.width.toFloat(), bitmap.height.toFloat())
                    }
                }

                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                detector.setCapturedBitmap(rotatedBitmap)
                detector.detect(rotatedBitmap)
            }
        })
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
            binding.resultText.text = "No text or object detected."
            speakText("No text or object detected in the image. Please reposition the camera and try again.")
            binding.scanButton.isEnabled = true
            isProcessing = false
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        val originalBitmap = detector.getCapturedBitmap()
        if (originalBitmap == null) {
            Log.e(TAG, "Original Bitmap is missing for ML Kit recognition.")
            onEmptyDetect()
            return
        }

        val mlExecutor = Executors.newSingleThreadExecutor()
        mlExecutor.execute {
            val recognitionTasks = boundingBoxes.map { box ->
                val croppedBitmap = cropBitmap(originalBitmap, box)
                val mlKitImage = InputImage.fromBitmap(croppedBitmap, 0)
                mlKitRecognizer.process(mlKitImage)
            }

            Tasks.whenAllSuccess<Text>(recognitionTasks)
                .addOnSuccessListener { visionTexts ->
                    val combinedText = StringBuilder()
                    for (visionText in visionTexts) {
                        val recognizedText = visionText.text.trim()
                        if (recognizedText.isNotEmpty()) {
                            combinedText.append(recognizedText).append(". ")
                        }
                    }
                    val finalResult = combinedText.toString().trim()
                    updateUiWithResults(finalResult, inferenceTime, boundingBoxes)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed for one or more images.", e)
                    val errorMsg = "Error: ${e.message}"
                    updateUiWithResults(errorMsg, inferenceTime, boundingBoxes)
                }
                .addOnCompleteListener {
                    mlExecutor.shutdown()
                }
        }
    }

    private fun updateUiWithResults(finalResult: String, inferenceTime: Long, boundingBoxes: List<BoundingBox>) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms + OCR"
            binding.overlay.setResults(boundingBoxes)

            if (finalResult.isNotEmpty()) {
                binding.resultText.text = finalResult
                if (!finalResult.startsWith("Error:")) {
                    speakText(finalResult)
                }
            } else {
                binding.resultText.text = "Text blocks detected, but no characters recognized."
                speakText("Text blocks detected, but no readable text found.")
            }

            isProcessing = false
            binding.scanButton.isEnabled = true
        }
    }

    private fun cropBitmap(original: Bitmap, box: BoundingBox): Bitmap {
        val imgWidth = original.width.toFloat()
        val imgHeight = original.height.toFloat()
        val paddingX = imgWidth * 0.02f
        val paddingY = imgHeight * 0.02f

        val left = (box.x1 * imgWidth - paddingX).coerceAtLeast(0f)
        val top = (box.y1 * imgHeight - paddingY).coerceAtLeast(0f)
        val right = (box.x2 * imgWidth + paddingX).coerceAtMost(imgWidth)
        val bottom = (box.y2 * imgHeight + paddingY).coerceAtMost(imgHeight)

        val x = left.toInt()
        val y = top.toInt()
        val width = (right - left).toInt()
        val height = (bottom - top).toInt()

        if (width <= 0 || height <= 0) {
            return Bitmap.createBitmap(original, 0, 0, min(1, original.width), min(1, original.height))
        }

        val finalX = x.coerceAtLeast(0).coerceAtMost(original.width)
        val finalY = y.coerceAtLeast(0).coerceAtMost(original.height)
        val finalWidth = width.coerceAtMost(original.width - finalX)
        val finalHeight = height.coerceAtMost(original.height - finalY)

        return Bitmap.createBitmap(original, finalX, finalY, finalWidth, finalHeight)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        detector.clear()
        cameraExecutor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "TextReader"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).toTypedArray()
    }
}
