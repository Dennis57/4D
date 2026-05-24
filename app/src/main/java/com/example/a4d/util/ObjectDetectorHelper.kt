package com.example.a4d.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import android.content.res.AssetFileDescriptor
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

class ObjectDetectorHelper(
    val context: Context,
    val objectDetectorListener: DetectorListener?
) {
    private var interpreter: Interpreter? = null

    private val labels = listOf(
        "Drowsiness/eyes_state/open",
        "Drowsiness/eyes_state/close",
        "Drowsiness/yawning/with_hand",
        "Drowsiness/yawning/without_hand",
        "Distraction/gaze/looking_road",
        "Distraction/gaze/not_looking_road",
        "Distraction/talking",
        "Distraction/driver_actions/safe_drive",
        "Distraction/driver_actions/drinking",
        "Distraction/driver_actions/hair_and_makeup",
        "Distraction/driver_actions/phonecall_left",
        "Distraction/driver_actions/phonecall_right",
        "Distraction/driver_actions/radio",
        "Distraction/driver_actions/reach_backseat",
        "Distraction/driver_actions/reach_side",
        "Distraction/driver_actions/talking_to_passenger",
        "Distraction/driver_actions/texting_left",
        "Distraction/driver_actions/texting_right",
        "Distraction/driver_actions/change_gear"
    )

    private var inputW = 640
    private var inputH = 640
    private var numClasses = labels.size

    companion object {
        private const val CONF_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD  = 0.45f
        private const val MODEL_FILE     = "best_float32.tflite"
    }

    init { setupObjectDetector() }

    fun setupObjectDetector() {
        try {
            val assetList = context.assets.list("")
            android.util.Log.d("TFLite", "Assets found: ${assetList?.joinToString()}")

            val fd = context.assets.openFd(MODEL_FILE)
            android.util.Log.d("TFLite", "Model fd size: ${fd.declaredLength} bytes")

            val model: MappedByteBuffer = context.assets.openFd(MODEL_FILE).let { fd ->
                FileInputStream(fd.fileDescriptor).channel
                    .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
            interpreter = Interpreter(model, Interpreter.Options().apply { numThreads = 4 })

            val inShape  = interpreter!!.getInputTensor(0).shape()  // [1, H, W, 3]
            inputH = inShape[1]; inputW = inShape[2]

            val outShape = interpreter!!.getOutputTensor(0).shape() // [1, 4+nc, anchors]
            numClasses   = outShape[1] - 4

        } catch (e: Exception) {
            objectDetectorListener?.onError("TFLite init failed: ${e.message}")
        }
    }

    fun detect(bitmap: Bitmap, imageRotation: Int) {
        val interp = interpreter ?: return
        val startTime = SystemClock.uptimeMillis()

        // 1. Rotate + resize bitmap manually (no support lib needed)
        val rotated = rotateBitmap(bitmap, imageRotation)
        val resized  = Bitmap.createScaledBitmap(rotated, inputW, inputH, true)

        // 2. Fill input ByteBuffer: RGBA_8888 bitmap → float32 RGB / 255
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputH * inputW * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputW * inputH)
        resized.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
        for (px in pixels) {
            inputBuffer.putFloat(((px shr 16) and 0xFF) / 255f)  // R
            inputBuffer.putFloat(((px shr  8) and 0xFF) / 255f)  // G
            inputBuffer.putFloat(( px         and 0xFF) / 255f)  // B
        }
        inputBuffer.rewind()

        // 3. Prepare output: [1, (4+nc), numAnchors]
        val outShape   = interp.getOutputTensor(0).shape()
        val numAnchors = outShape[2]
        val rawOutput  = Array(1) { Array(outShape[1]) { FloatArray(numAnchors) } }

        try {
            interp.run(inputBuffer, rawOutput)
        } catch (e: Exception) {
            objectDetectorListener?.onError("Inference failed: ${e.message}")
            return
        }

        val inferenceTime = SystemClock.uptimeMillis() - startTime
        val detections    = parseYoloOutput(rawOutput[0], numAnchors, bitmap.width, bitmap.height)

        objectDetectorListener?.onResults(
            DetectionResults(detections), inferenceTime, bitmap.height, bitmap.width
        )
    }

    private fun rotateBitmap(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun parseYoloOutput(
        output: Array<FloatArray>, numAnchors: Int, origW: Int, origH: Int
    ): List<Detection> {
        val candidates = mutableListOf<RawDetection>()
        for (a in 0 until numAnchors) {
            var maxScore = 0f; var classIdx = 0
            for (c in 0 until numClasses) {
                val s = output[4 + c][a]
                if (s > maxScore) { maxScore = s; classIdx = c }
            }
            if (maxScore < CONF_THRESHOLD) continue

            val cx = output[0][a]; val cy = output[1][a]
            val bw = output[2][a]; val bh = output[3][a]
            val x1 = ((cx - bw / 2f) * origW).coerceIn(0f, origW.toFloat())
            val y1 = ((cy - bh / 2f) * origH).coerceIn(0f, origH.toFloat())
            val x2 = ((cx + bw / 2f) * origW).coerceIn(0f, origW.toFloat())
            val y2 = ((cy + bh / 2f) * origH).coerceIn(0f, origH.toFloat())
            candidates.add(RawDetection(x1, y1, x2, y2, maxScore, classIdx))
        }
        return applyNms(candidates).map { raw ->
            val label = if (raw.classIdx in labels.indices) labels[raw.classIdx] else "Unknown"
            Detection(listOf(Category(label, raw.score, raw.classIdx)))
        }
    }

    private fun applyNms(detections: List<RawDetection>): List<RawDetection> {
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val kept   = mutableListOf<RawDetection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { iou(best, it) > IOU_THRESHOLD }
        }
        return kept
    }

    private fun iou(a: RawDetection, b: RawDetection): Float {
        val ix1 = maxOf(a.x1, b.x1); val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2); val iy2 = minOf(a.y2, b.y2)
        val inter = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
        return inter / ((a.x2-a.x1)*(a.y2-a.y1) + (b.x2-b.x1)*(b.y2-b.y1) - inter + 1e-6f)
    }

    private data class RawDetection(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val score: Float, val classIdx: Int
    )

    fun clearObjectDetector() { interpreter?.close(); interpreter = null }

    data class DetectionResults(private val d: List<Detection>) { fun detections() = d }
    data class Detection(private val c: List<Category>)         { fun categories() = c }
    data class Category(private val l: String, private val s: Float, private val i: Int) {
        fun categoryName() = l; fun score() = s; fun index() = i
    }
    interface DetectorListener {
        fun onError(error: String)
        fun onResults(result: DetectionResults?, inferenceTime: Long, imageHeight: Int, imageWidth: Int)
    }
}