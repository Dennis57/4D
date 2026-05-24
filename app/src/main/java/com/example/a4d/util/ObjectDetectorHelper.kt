package com.example.a4d.util

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod
import java.nio.MappedByteBuffer

class ObjectDetectorHelper(
    val context: Context,
    val objectDetectorListener: DetectorListener?
) {
    private var interpreter: Interpreter? = null
    private val labels = listOf(
        "Drowsiness/eyes_state/open", "Drowsiness/eyes_state/close", "Drowsiness/yawning/with_hand",
        "Drowsiness/yawning/without_hand", "Distraction/gaze/looking_road", "Distraction/gaze/not_looking_road",
        "Distraction/talking", "Distraction/driver_actions/safe_drive", "Distraction/driver_actions/drinking",
        "Distraction/driver_actions/hair_and_makeup", "Distraction/driver_actions/phonecall_left",
        "Distraction/driver_actions/phonecall_right", "Distraction/driver_actions/radio",
        "Distraction/driver_actions/reach_backseat", "Distraction/driver_actions/reach_side",
        "Distraction/driver_actions/talking_to_passenger", "Distraction/driver_actions/texting_left",
        "Distraction/driver_actions/texting_right", "Distraction/driver_actions/change_gear"
    )

    private var inputImageWidth = 0
    private var inputImageHeight = 0

    init {
        setupObjectDetector()
    }

    fun setupObjectDetector() {
        try {
            val model: MappedByteBuffer = FileUtil.loadMappedFile(context, "best.tflite")
            val options = Interpreter.Options()
            interpreter = Interpreter(model, options)

            // Detect input shape [1, height, width, 3]
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            inputImageHeight = inputShape?.get(1) ?: 320
            inputImageWidth = inputShape?.get(2) ?: 320

        } catch (e: Exception) {
            objectDetectorListener?.onError("TFLite Interpreter failed to initialize: ${e.message}")
            e.printStackTrace()
        }
    }

    fun detect(bitmap: Bitmap, imageRotation: Int) {
        val interpreter = this.interpreter ?: return

        var inferenceTime = SystemClock.uptimeMillis()

        // 1. Preprocess the image
        // Most TFLite models use 127.5 mean/std for normalization.
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputImageHeight, inputImageWidth, ResizeMethod.BILINEAR))
            .add(Rot90Op(-imageRotation / 90))
            .add(NormalizeOp(127.5f, 127.5f))
            .build()

        var tensorImage = TensorImage(interpreter.getInputTensor(0).dataType())
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // 2. Prepare output buffers for standard Object Detection models
        // Typical SSD outputs: [1, 10, 4], [1, 10], [1, 10], [1]
        val outputLocations = Array(1) { Array(10) { FloatArray(4) } }
        val outputClasses = Array(1) { FloatArray(10) }
        val outputScores = Array(1) { FloatArray(10) }
        val numDetections = FloatArray(1)

        val outputs = mutableMapOf<Int, Any>(
            0 to outputLocations,
            1 to outputClasses,
            2 to outputScores,
            3 to numDetections
        )

        try {
            interpreter.runForMultipleInputsOutputs(arrayOf(tensorImage.buffer), outputs)
            
            inferenceTime = SystemClock.uptimeMillis() - inferenceTime

            val detections = mutableListOf<Detection>()
            for (i in 0 until numDetections[0].toInt().coerceAtMost(10)) {
                if (outputScores[0][i] > 0.5f) {
                    val classIndex = outputClasses[0][i].toInt()
                    val label = if (classIndex in labels.indices) labels[classIndex] else "Unknown"
                    detections.add(
                        Detection(
                            categories = listOf(Category(label, outputScores[0][i], classIndex))
                        )
                    )
                }
            }

            objectDetectorListener?.onResults(
                DetectionResults(detections),
                inferenceTime,
                bitmap.height,
                bitmap.width
            )
        } catch (e: Exception) {
            // If the model isn't a standard 4-output detector, this might fail.
            objectDetectorListener?.onError("Inference failed: ${e.message}")
        }
    }

    fun clearObjectDetector() {
        interpreter?.close()
        interpreter = null
    }

    // Helper classes to maintain compatibility with existing Activity logic
    data class DetectionResults(private val detections: List<Detection>) {
        fun detections() = detections
    }

    data class Detection(private val categories: List<Category>) {
        fun categories() = categories
    }

    data class Category(private val label: String, private val score: Float, private val index: Int) {
        fun categoryName() = label
        fun score() = score
        fun index() = index
    }

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(
            result: DetectionResults?,
            inferenceTime: Long,
            imageHeight: Int,
            imageWidth: Int
        )
    }
}
