package com.example.pedestriantrafficlightdetection

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.widget.ImageView
import androidx.camera.core.ImageProxy
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.*
import kotlin.math.max
import kotlin.math.min

class DataProcess(val context: Context, private val ttsManager: TTSManager, // Now initialized directly through the constructor
                  private var imageView: ImageView
) {

    lateinit var classes: Array<String>
    private var lastDetectedFrame: MutableMap<String, Boolean> = mutableMapOf("green" to false, "red" to false)

    // Initialize the DetectionResultHandler with context and ImageView
    // This initialization now happens in an init block because it depends on properties initialized by the constructor
    private val detectionResultHandler: DetectionResultHandler =
        DetectionResultHandler(activity = Activity(), imageView)

    companion object {
        const val BATCH_SIZE = 1
        const val INPUT_SIZE = 640
        const val PIXEL_SIZE = 3
        const val FILE_NAME = "final.onnx"
        const val LABEL_NAME = "yolov8n.txt"
    }

    fun imageToBitmap(imageProxy: ImageProxy): Bitmap {
        val bitmap = imageProxy.toBitmap()
        val bitmap640 = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val matrix = Matrix()
        matrix.postRotate(90f)
        return Bitmap.createBitmap(bitmap640, 0, 0, INPUT_SIZE, INPUT_SIZE, matrix, true)
    }

    fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val imageSTD = 255.0f

        val cap = BATCH_SIZE * PIXEL_SIZE * INPUT_SIZE * INPUT_SIZE
        val order = ByteOrder.nativeOrder()
        val buffer = ByteBuffer.allocateDirect(cap * Float.SIZE_BYTES).order(order).asFloatBuffer()

        val area = INPUT_SIZE * INPUT_SIZE
        val bitmapData = IntArray(area) //한 사진에서 대한 정보, 640x640 사이즈
        bitmap.getPixels(
            bitmapData,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height
        ) // 배열에 정보 담기

        //배열에서 하나씩 가져와서 buffer 에 담기
        for (i in 0 until INPUT_SIZE - 1) {
            for (j in 0 until INPUT_SIZE - 1) {
                val idx = INPUT_SIZE * i + j
                val pixelValue = bitmapData[idx]
                // 위에서 부터 차례대로 R 값 추출, G 값 추출, B값 추출 -> 255로 나누어서 0~1 사이로 정규화
                buffer.put(idx, ((pixelValue shr 16 and 0xff) / imageSTD))
                buffer.put(idx + area, ((pixelValue shr 8 and 0xff) / imageSTD))
                buffer.put(idx + area * 2, ((pixelValue and 0xff) / imageSTD))
                //원리 bitmap == ARGB 형태의 32bit, R값의 시작은 16bit (16 ~ 23bit 가 R영역), 따라서 16bit 를 쉬프트
                //그럼 A값이 사라진 RGB 값인 24bit 가 남는다. 이후 255와 AND 연산을 통해 맨 뒤 8bit 인 R값만 가져오고, 255로 나누어 정규화를 한다.
                //다시 8bit 를 쉬프트 하여 R값을 제거한 G,B 값만 남은 곳에 다시 AND 연산, 255 정규화, 다시 반복해서 RGB 값을 buffer 에 담는다.
            }
        }
        buffer.rewind() // position 0
        return buffer
    }

    fun loadModel() {
        // onnx 파일 불러오기
        val assetManager = context.assets
        val outputFile = File(context.filesDir.toString() + "/" + FILE_NAME)

        assetManager.open(FILE_NAME).use { inputStream ->
            FileOutputStream(outputFile).use { outputStream ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
            }
        }
    }

    fun loadLabel() {
        // txt 파일 불러오기
        BufferedReader(InputStreamReader(context.assets.open(LABEL_NAME))).use { reader ->
            var line: String?
            val classList = ArrayList<String>()
            while (reader.readLine().also { line = it } != null) {
                classList.add(line!!)
            }
            classes = classList.toTypedArray()
        }
    }

    fun outputsToNPMSPredictions(outputs: Array<*>): ArrayList<Result> {
        val confidenceThreshold = 0.3f
        val results = ArrayList<Result>()
        val rows: Int
        val cols: Int

        (outputs[0] as Array<*>).also {
            rows = it.size
            cols = (it[0] as FloatArray).size
        }

        val output = Array(cols) { FloatArray(rows) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                output[j][i] = ((((outputs[0]) as Array<*>)[i]) as FloatArray)[j]
            }
        }

        // Reset detection flags for this frame
        val detectedColorsThisFrame: MutableSet<String> = mutableSetOf()

        for (i in 0 until cols) {
            var detectionClass: Int = -1
            var maxScore = 0f
            val classArray = FloatArray(classes.size)
            System.arraycopy(output[i], 4, classArray, 0, classes.size)
            for (j in classes.indices) {
                if (classArray[j] > maxScore) {
                    detectionClass = j
                    maxScore = classArray[j]
                }
            }

            if (maxScore > confidenceThreshold) {
                val xPos = output[i][0]
                val yPos = output[i][1]
                val width = output[i][2]
                val height = output[i][3]
                val rectF = RectF(
                    max(0f, xPos - width / 2f),
                    max(0f, yPos - height / 2f),
                    min(INPUT_SIZE - 1f, xPos + width / 2f),
                    min(INPUT_SIZE - 1f, yPos + height / 2f)
                )
                val result = Result(detectionClass, maxScore, rectF)
                results.add(result)

                val detectedClassLabel = classes[detectionClass].toLowerCase(Locale.ROOT)
                detectedColorsThisFrame.add(detectedClassLabel)
            }
        }

        // Check for color detection changes
        detectedColorsThisFrame.forEach { color ->
            if (!lastDetectedFrame.getOrDefault(color, false)) {
                handleDetectionResult(color)
                detectionResultHandler.updateImageViewBasedOnDetection(color)
            }
            lastDetectedFrame[color] = true
        }

        // Reset detection state for colors not detected in this frame
        lastDetectedFrame.keys.forEach { color ->
            if (!detectedColorsThisFrame.contains(color)) {
                lastDetectedFrame[color] = false
            }
        }

        return nms(results) // Ensure the nms function is defined elsewhere
    }


    private fun nms(results: ArrayList<Result>): ArrayList<Result> {
        val list = ArrayList<Result>()

        for (i in classes.indices) {
            //1.클래스 (라벨들) 중에서 가장 높은 확률값을 가졌던 클래스 찾기
            val pq = PriorityQueue<Result>(50) { o1, o2 ->
                o1.score.compareTo(o2.score)
            }
            val classResults = results.filter { it.classIndex == i }
            pq.addAll(classResults)

            //NMS 처리
            while (pq.isNotEmpty()) {
                // 큐 안에 속한 최대 확률값을 가진 class 저장
                val detections = pq.toTypedArray()
                val max = detections[0]
                list.add(max)
                pq.clear()

                // 교집합 비율 확인하고 50%넘기면 제거
                for (k in 1 until detections.size) {
                    val detection = detections[k]
                    val rectF = detection.rectF
                    val iouThresh = 0.7f
                    if (boxIOU(max.rectF, rectF) < iouThresh) {
                        pq.add(detection)
                    }
                }
            }
        }
        return list
    }

    // 겹치는 비율 (교집합/합집합)
    private fun boxIOU(a: RectF, b: RectF): Float {
        return boxIntersection(a, b) / boxUnion(a, b)
    }

    //교집합
    private fun boxIntersection(a: RectF, b: RectF): Float {
        // x1, x2 == 각 rect 객체의 중심 x or y값, w1, w2 == 각 rect 객체의 넓이 or 높이
        val w = overlap(
            (a.left + a.right) / 2f, a.right - a.left,
            (b.left + b.right) / 2f, b.right - b.left
        )
        val h = overlap(
            (a.top + a.bottom) / 2f, a.bottom - a.top,
            (b.top + b.bottom) / 2f, b.bottom - b.top
        )

        return if (w < 0 || h < 0) 0f else w * h
    }

    // 합집합
    private fun boxUnion(a: RectF, b: RectF): Float {
        val i: Float = boxIntersection(a, b)
        return (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - i
    }

    //서로 겹치는 부분의 길이
    private fun overlap(x1: Float, w1: Float, x2: Float, w2: Float): Float {
        val l1 = x1 - w1 / 2
        val l2 = x2 - w2 / 2
        val left = max(l1, l2)
        val r1 = x1 + w1 / 2
        val r2 = x2 + w2 / 2
        val right = min(r1, r2)
        return right - left
    }

    // Method where you handle the detection result
    private fun handleDetectionResult(detectedColor: String) {
        // Invoke TTS based on the detected color
        when (detectedColor) {
            "green" -> ttsManager.speak("초록불입니다. 건너가도 됩니다.")
            "red" -> ttsManager.speak("빨간불입니다. 정지하세요.")
        }
    }

}