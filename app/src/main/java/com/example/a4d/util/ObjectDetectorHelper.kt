package com.example.a4d.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.*

class ObjectDetectorHelper(
    val context: Context,
    val objectDetectorListener: DetectorListener?
) {

    private var interpreter: Interpreter? = null
    private var poseLandmarker: PoseLandmarker? = null

    // 15 classes — matches config.py ALL_CLASSES order exactly
    private val labels = listOf(
        "Drowsiness/yawning/with_hand",
        "Drowsiness/yawning/without_hand",
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

    private val displayNames = mapOf(
        "Drowsiness/yawning/with_hand"                    to "Yawning (with hand)",
        "Drowsiness/yawning/without_hand"                 to "Yawning",
        "Distraction/talking"                             to "Talking",
        "Distraction/driver_actions/safe_drive"           to "Safe Drive",
        "Distraction/driver_actions/drinking"             to "Drinking",
        "Distraction/driver_actions/hair_and_makeup"      to "Hair & Makeup",
        "Distraction/driver_actions/phonecall_left"       to "Phone Call (Left)",
        "Distraction/driver_actions/phonecall_right"      to "Phone Call (Right)",
        "Distraction/driver_actions/radio"                to "Adjusting Radio",
        "Distraction/driver_actions/reach_backseat"       to "Reaching Backseat",
        "Distraction/driver_actions/reach_side"           to "Reaching Side",
        "Distraction/driver_actions/talking_to_passenger" to "Talking to Passenger",
        "Distraction/driver_actions/texting_left"         to "Texting (Left)",
        "Distraction/driver_actions/texting_right"        to "Texting (Right)",
        "Distraction/driver_actions/change_gear"          to "Changing Gear",
    )

    companion object {
        private const val MODEL_FILE      = "dmd_dual_simplified_float32.tflite"
        private const val POSE_TASK_FILE  = "pose_landmarker_full.task"
        private const val CONF_THRESHOLD  = 0.40f
        private const val IMG_SIZE        = 224
        private const val LANDMARK_DIM    = 132   // 33 keypoints × 4
        private const val FEATURE_DIM     = 149   // 132 + 17 engineered

        // ImageNet normalization (matches training)
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD  = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    init {
        setupObjectDetector()
    }

    fun setupObjectDetector() {
        try {
            // ── TFLite interpreter ────────────────────────────────────────
            val modelBuffer = context.assets.openFd(MODEL_FILE).let { fd ->
                FileInputStream(fd.fileDescriptor).channel
                    .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
            interpreter = Interpreter(modelBuffer, Interpreter.Options().apply {
                numThreads = 4
            })

            // Add right after: interpreter = Interpreter(modelBuffer, ...)
            val inputDetails  = interpreter!!.getInputTensorCount()
            val outputDetails = interpreter!!.getOutputTensorCount()
            android.util.Log.d("ModelInfo", "Inputs: $inputDetails, Outputs: $outputDetails")

            for (i in 0 until interpreter!!.getInputTensorCount()) {
                val t = interpreter!!.getInputTensor(i)
                android.util.Log.d("ModelInfo", "Input[$i] name=${t.name()} shape=${t.shape().toList()}")
            }

// Log all 15 class labels with their index
            labels.forEachIndexed { idx, name ->
                android.util.Log.d("ModelInfo", "Class[$idx] = $name")
            }

            // ── MediaPipe Pose Landmarker ─────────────────────────────────
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(POSE_TASK_FILE)
                .build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setNumPoses(1)
                .build()
            poseLandmarker = PoseLandmarker.createFromOptions(context, options)

        } catch (e: Exception) {
            objectDetectorListener?.onError("Init failed: ${e.message}")
        }
    }

    fun detect(bitmap: Bitmap, imageRotation: Int) {
        val interp = interpreter ?: return
        val pose   = poseLandmarker ?: return
        val startTime = SystemClock.uptimeMillis()

        // ── 1. Rotate bitmap to upright ───────────────────────────────────
        val rotated = rotateBitmap(bitmap, imageRotation)

        // ── 2. MediaPipe pose landmarks ───────────────────────────────────
        val mpImage = BitmapImageBuilder(rotated).build()
        val poseResult = pose.detect(mpImage)

        val lmFeatures: FloatArray
        if (poseResult.landmarks().isNotEmpty()) {
            val landmarks = poseResult.landmarks()[0]
            // Raw: 33 × [x, y, z, visibility] → flatten to 132 floats
            val raw = FloatArray(33 * 4)
            for (i in 0 until 33) {
                val lm = landmarks[i]
                raw[i * 4 + 0] = lm.x()
                raw[i * 4 + 1] = lm.y()
                raw[i * 4 + 2] = lm.z()
                raw[i * 4 + 3] = lm.presence().orElse(0f)
            }
            val normalized  = normalizeLandmarks(raw)
            lmFeatures = engineerFeatures(normalized)
        } else {
            // Fallback: zero vector if no pose detected
            lmFeatures = FloatArray(FEATURE_DIM) { 0f }
        }

        // ── 3. Prepare image input buffer (ImageNet normalized) ───────────
        val resized = Bitmap.createScaledBitmap(rotated, IMG_SIZE, IMG_SIZE, true)
        val imgBuffer = ByteBuffer.allocateDirect(1 * IMG_SIZE * IMG_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        resized.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)
        for (px in pixels) {
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr  8) and 0xFF) / 255f
            val b = ( px         and 0xFF) / 255f
            imgBuffer.putFloat((r - IMAGENET_MEAN[0]) / IMAGENET_STD[0])
            imgBuffer.putFloat((g - IMAGENET_MEAN[1]) / IMAGENET_STD[1])
            imgBuffer.putFloat((b - IMAGENET_MEAN[2]) / IMAGENET_STD[2])
        }
        imgBuffer.rewind()

        // ── 4. Prepare pose input buffer ──────────────────────────────────
        val poseBuffer = ByteBuffer.allocateDirect(1 * FEATURE_DIM * 4)
            .order(ByteOrder.nativeOrder())
        for (v in lmFeatures) poseBuffer.putFloat(v)
        poseBuffer.rewind()

        // ── 5. Run inference ──────────────────────────────────────────────
        // Input  0 = image [1, 224, 224, 3]
        // Input  1 = pose  [1, 149]
        // Output 344 = logits [1, 15]
        val logits = Array(1) { FloatArray(labels.size) }
        val inputs  = arrayOf<Any>(imgBuffer, poseBuffer)
        val outputs = mutableMapOf<Int, Any>(0 to logits)

        try {
            val currentInterp = interpreter ?: return
            currentInterp.runForMultipleInputsOutputs(inputs, outputs)
        } catch (e: Exception) {
            // Check if interpreter was closed during inference (e.g. Activity finishing)
            if (interpreter != null) {
                objectDetectorListener?.onError("Inference failed: ${e.message}")
            }
            return
        }

        val inferenceTime = SystemClock.uptimeMillis() - startTime

        // ── 6. Softmax + threshold ────────────────────────────────────────
        val probs = softmax(logits[0])
        val maxIdx   = probs.indices.maxByOrNull { probs[it] } ?: 0
        val maxScore = probs[maxIdx]
        val label    = labels[maxIdx]

        val detections = if (maxScore >= CONF_THRESHOLD) {
            val display = displayNames[label] ?: label
            listOf(Detection(listOf(Category(display, maxScore, maxIdx))))
        } else {
            val safeIdx = 3
            val safeLabel = labels[safeIdx]
            val display = displayNames[safeLabel] ?: safeLabel
            listOf(Detection(listOf(Category(display, probs[safeIdx], safeIdx))))
        }

        objectDetectorListener?.onResults(
            DetectionResults(detections), inferenceTime, bitmap.height, bitmap.width
        )
    }

    // ── Landmark normalisation (mirrors Python normalise()) ───────────────────
    private fun normalizeLandmarks(raw: FloatArray): FloatArray {
        val L = Array(33) { i -> floatArrayOf(raw[i*4], raw[i*4+1], raw[i*4+2], raw[i*4+3]) }
        val mx = (L[11][0] + L[12][0]) / 2f
        val my = (L[11][1] + L[12][1]) / 2f
        val sw = max(sqrt(((L[11][0]-L[12][0]).pow(2) + (L[11][1]-L[12][1]).pow(2)).toDouble()).toFloat(), 1e-6f)
        val norm = FloatArray(33 * 4)
        for (i in 0 until 33) {
            norm[i*4+0] = (L[i][0] - mx) / sw
            norm[i*4+1] = (L[i][1] - my) / sw
            norm[i*4+2] =  L[i][2]       / sw
            norm[i*4+3] =  L[i][3]
        }
        return norm
    }

    // ── Feature engineering (mirrors Python engineer()) ───────────────────────
    private fun engineerFeatures(lmNorm: FloatArray): FloatArray {
        // Reshape to [33][4]
        val L = Array(33) { i -> floatArrayOf(lmNorm[i*4], lmNorm[i*4+1], lmNorm[i*4+2], lmNorm[i*4+3]) }

        fun dist(i: Int, j: Int) = sqrt(
            (L[i][0]-L[j][0]).pow(2) + (L[i][1]-L[j][1]).pow(2) + (L[i][2]-L[j][2]).pow(2).toDouble()
        ).toFloat()

        fun angle(a: Int, b: Int, c: Int): Float {
            val ba = floatArrayOf(L[a][0]-L[b][0], L[a][1]-L[b][1], L[a][2]-L[b][2])
            val bc = floatArrayOf(L[c][0]-L[b][0], L[c][1]-L[b][1], L[c][2]-L[b][2])
            val dot = ba[0]*bc[0] + ba[1]*bc[1] + ba[2]*bc[2]
            val magBa = sqrt((ba[0].pow(2)+ba[1].pow(2)+ba[2].pow(2)).toDouble()).toFloat()
            val magBc = sqrt((bc[0].pow(2)+bc[1].pow(2)+bc[2].pow(2)).toDouble()).toFloat()
            return acos((dot / (magBa * magBc + 1e-6f)).coerceIn(-1f, 1f))
        }

        val cx = (L[11][0] + L[12][0]) / 2f

        val engineered = floatArrayOf(
            dist(15, 0), dist(16, 0), dist(15, 16),
            L[11][1] - L[15][1], L[12][1] - L[16][1],
            L[23][1] - L[15][1], L[24][1] - L[16][1],
            angle(11, 13, 15), angle(12, 14, 16),
            dist(11, 12),
            L[7][1]  - L[8][1],
            L[15][0] - cx, L[16][0] - cx,
            L[15][2] - L[11][2], L[16][2] - L[12][2],
            L[15][3], L[16][3]
        )   // 17 features

        return lmNorm + engineered   // 132 + 17 = 149
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val maxVal = logits.max()
        val exps   = logits.map { exp((it - maxVal).toDouble()).toFloat() }.toFloatArray()
        val sum    = exps.sum()
        return exps.map { it / sum }.toFloatArray()
    }

    private fun rotateBitmap(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun clearObjectDetector() {
        interpreter?.close(); interpreter = null
        poseLandmarker?.close(); poseLandmarker = null
    }

    // ── Data classes (DetectionActivity unchanged) ────────────────────────────
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

// FloatArray concatenation helper
private operator fun FloatArray.plus(other: FloatArray): FloatArray {
    val result = FloatArray(this.size + other.size)
    this.copyInto(result); other.copyInto(result, this.size)
    return result
}

private fun Float.pow(n: Int) = this.toDouble().pow(n).toFloat()