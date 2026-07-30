package com.sudoku.game.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

/**
 * SudokuRecognizer - 数独图像识别引擎
 *
 * 从原Python项目 sudoku_recognizer.py 完整移植：
 *   1. 灰度化与缩放
 *   2. 投影法网格检测 (median * 0.8 阈值)
 *   3. 81单元格分割 (8%边距裁剪)
 *   4. 墨水检测 (median * 0.7, ≥3%墨水占比)
 *   5. 32×32居中归一化 (双线性插值)
 *   6. ONNX Runtime CNN推理
 *
 * 模型输入: (N, 1, 32, 32) float32 [0,1] 白底黑字
 * 模型输出: 10类 logits (数字0-9)
 */
class SudokuRecognizer private constructor(
    private val session: OrtSession,
    private val inputName: String,
    private val outputName: String
) {
    companion object {
        private const val IMG_SIZE = 32
        private const val MAX_SIZE = 800
        private const val MAX_DIGIT_SIZE = 28

        private var env: OrtEnvironment? = null

        /**
         * 从assets加载ONNX模型
         * 优先加载手写体模型，不存在则用印刷体模型
         */
        fun create(context: Context): SudokuRecognizer {
            if (env == null) {
                env = OrtEnvironment.getEnvironment()
            }

            val assetManager = context.assets
            // 优先手写体模型
            val modelFileName = try {
                assetManager.open("sudoku_digit_handwritten.onnx").use { _
                    -> "sudoku_digit_handwritten.onnx"
                }
            } catch (e: Exception) {
                "sudoku_digit.onnx"
            }

            // 将模型从assets复制到临时文件（ONNX Runtime需要文件路径）
            val modelFile = File(context.cacheDir, modelFileName)
            if (!modelFile.exists()) {
                assetManager.open(modelFileName).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            val session = env!!.createSession(modelFile.absolutePath, sessionOptions)
            val inputName = session.inputNames.first()
            val outputName = session.outputNames.first()

            return SudokuRecognizer(session, inputName, outputName)
        }
    }

    /**
     * 识别结果数据类
     */
    data class RecognitionResult(
        val board: Array<IntArray>,      // 9x9 数独矩阵
        val success: Boolean,
        val message: String
    )

    /**
     * 主识别流程
     * @param bitmap 输入图像Bitmap
     * @return RecognitionResult
     */
    fun recognize(bitmap: Bitmap): RecognitionResult {
        // ① 灰度化与缩放
        val gray = loadGrayscale(bitmap)
        val rows = gray.size
        val cols = gray[0].size

        // ② 投影法网格检测
        val gridResult = findGridProjection(gray)
            ?: return RecognitionResult(Array(9) { IntArray(9) }, false, "网格检测失败")

        val (hLines, vLines) = gridResult
        if (hLines.size < 10 || vLines.size < 10) {
            return RecognitionResult(Array(9) { IntArray(9) }, false, "网格线数量不足")
        }

        // ③ 构建81个单元格
        val cells = buildCellsFromLines(hLines, vLines, rows, cols)

        // ④ 逐格检测墨水并准备识别
        val sudoku = Array(9) { IntArray(9) }
        val digitPositions = mutableListOf<Pair<Int, Int>>()
        val cellBuffers = mutableListOf<FloatArray>()

        for (r in 0..8) {
            for (c in 0..8) {
                val (r1, r2, c1, c2) = cells[r][c]
                val (hasDigit, buf) = prepareCell(gray, r1, r2, c1, c2)
                if (hasDigit) {
                    digitPositions.add(Pair(r, c))
                    cellBuffers.add(buf)
                }
            }
        }

        if (digitPositions.isEmpty()) {
            return RecognitionResult(sudoku, true, "未检测到数字")
        }

        // ⑤ 批量ONNX推理
        val predictions = predictBatch(cellBuffers)

        // ⑥ 填充结果
        for (i in digitPositions.indices) {
            val (r, c) = digitPositions[i]
            val (digit, _) = predictions[i]
            if (digit > 0) {
                sudoku[r][c] = digit
            }
        }

        return RecognitionResult(sudoku, true, "识别完成，检测到${digitPositions.size}个数字")
    }

    // ============================================================
    // 1. 图像预处理
    // ============================================================

    /**
     * Bitmap转灰度float数组，缩放到MAX_SIZE以内
     * 对应Python: load_grayscale()
     */
    private fun loadGrayscale(bitmap: Bitmap): Array<FloatArray> {
        var bmp = bitmap
        val w = bmp.width
        val h = bmp.height

        // 缩放
        if (max(h, w) > MAX_SIZE) {
            val scale = MAX_SIZE.toFloat() / max(h, w)
            val newW = (w * scale).toInt()
            val newH = (h * scale).toInt()
            bmp = Bitmap.createScaledBitmap(bmp, newW, newH, true)
        }

        val bw = bmp.width
        val bh = bmp.height
        val pixels = IntArray(bw * bh)
        bmp.getPixels(pixels, 0, bw, 0, 0, bw, bh)

        val gray = Array(bh) { FloatArray(bw) }
        for (i in 0 until bh) {
            for (j in 0 until bw) {
                val px = pixels[i * bw + j]
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                // ITU-R BT.601 luminance
                gray[i][j] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            }
        }
        return gray
    }

    // ============================================================
    // 2. 投影法网格检测
    // ============================================================

    /**
     * 计算中位数
     */
    private fun median(arr: Array<FloatArray>): Float {
        val flat = FloatArray(arr.size * arr[0].size)
        var idx = 0
        for (row in arr) {
            for (v in row) {
                flat[idx++] = v
            }
        }
        flat.sort()
        return flat[flat.size / 2]
    }

    /**
     * 计算中位数（一维数组）
     */
    private fun median(arr: FloatArray): Float {
        val sorted = arr.sortedArray()
        return sorted[sorted.size / 2]
    }

    /**
     * 投影阈值: median * 0.8
     */
    private fun projectionThreshold(arr: Array<FloatArray>): Float {
        return median(arr) * 0.8f
    }

    /**
     * 从投影数据中提取线的中心位置
     * 连续高于阈值的区域的中心点
     */
    private fun extractLineCenters(projection: FloatArray, threshVal: Float): List<Int> {
        val n = projection.size
        val lines = mutableListOf<Int>()
        var inLine = false
        var startIdx = 0

        for (i in 0 until n) {
            if (projection[i] > threshVal && !inLine) {
                startIdx = i
                inLine = true
            } else if (projection[i] <= threshVal && inLine) {
                val center = (startIdx + i - 1) / 2
                lines.add(center)
                inLine = false
            }
        }
        if (inLine) {
            val center = (startIdx + n - 1) / 2
            lines.add(center)
        }
        lines.sort()
        return lines
    }

    /**
     * 从二值图中找网格的四个边界
     */
    private fun findGridBounds(binary: Array<BooleanArray>): IntArray? {
        val rows = binary.size
        val cols = binary[0].size

        val rowSum = FloatArray(rows)
        val colSum = FloatArray(cols)
        for (r in 0 until rows) {
            var s = 0f
            for (c in 0 until cols) {
                if (binary[r][c]) s += 1f
            }
            rowSum[r] = s
        }
        for (c in 0 until cols) {
            var s = 0f
            for (r in 0 until rows) {
                if (binary[r][c]) s += 1f
            }
            colSum[c] = s
        }

        val maxRow = rowSum.maxOrNull() ?: 0f
        val maxCol = colSum.maxOrNull() ?: 0f
        if (maxRow == 0f || maxCol == 0f) return null

        val rowT = maxRow * 0.1f
        val colT = maxCol * 0.1f

        var gt = 0; var gb = 0; var gl = 0; var gr = 0
        for (r in 0 until rows) { if (rowSum[r] >= rowT) { gt = r; break } }
        for (r in rows - 1 downTo 0) { if (rowSum[r] >= rowT) { gb = r; break } }
        for (c in 0 until cols) { if (colSum[c] >= colT) { gl = c; break } }
        for (c in cols - 1 downTo 0) { if (colSum[c] >= colT) { gr = c; break } }

        if (gt == 0 && gb == 0 && gl == 0 && gr == 0) return null
        return intArrayOf(gt, gb, gl, gr)
    }

    /**
     * 投影法网格检测 —— 主方法
     * 返回 (hLines, vLines) 各10条线位置，或 null
     */
    private fun findGridProjection(gray: Array<FloatArray>): Pair<List<Int>, List<Int>>? {
        val rows = gray.size
        val cols = gray[0].size

        // 二值化: gray < thresh => true (墨水/网格线)
        val thresh = projectionThreshold(gray)
        val binary = Array(rows) { r -> BooleanArray(cols) { c -> gray[r][c] < thresh } }

        // 水平投影和垂直投影
        val hProj = FloatArray(rows)
        val vProj = FloatArray(cols)
        for (r in 0 until rows) {
            var s = 0f
            for (c in 0 until cols) {
                if (binary[r][c]) s += 1f
            }
            hProj[r] = s
        }
        for (c in 0 until cols) {
            var s = 0f
            for (r in 0 until rows) {
                if (binary[r][c]) s += 1f
            }
            vProj[c] = s
        }

        // 阈值：最大值的50%
        val hThreshVal = (hProj.maxOrNull() ?: 0f) * 0.5f
        val vThreshVal = (vProj.maxOrNull() ?: 0f) * 0.5f

        var hLines = extractLineCenters(hProj, hThreshVal)
        var vLines = extractLineCenters(vProj, vThreshVal)

        // 如果恰好找到>=10条，直接返回前10条
        if (hLines.size >= 10 && vLines.size >= 10) {
            return Pair(hLines.sorted().take(10), vLines.sorted().take(10))
        }

        // Fallback: 用边界等分
        val bounds = findGridBounds(binary) ?: return null
        val (gt, gb, gl, gr) = listOf(bounds[0], bounds[1], bounds[2], bounds[3])
        val gridH = gb - gt
        val gridW = gr - gl
        hLines = (0..9).map { (gt + (it / 9.0 * gridH).toInt()) }
        vLines = (0..9).map { (gl + (it / 9.0 * gridW).toInt()) }
        return Pair(hLines, vLines)
    }

    /**
     * 从10条水平线和10条垂直线构建81个单元格坐标
     * 返回: cells[r][c] = intArrayOf(r1, r2, c1, c2)
     */
    private fun buildCellsFromLines(
        hLines: List<Int>, vLines: List<Int>, rows: Int, cols: Int
    ): Array<Array<IntArray>> {
        val cells = Array(9) { Array(9) { IntArray(4) } }
        for (r in 0..8) {
            for (c in 0..8) {
                val ch = hLines[r + 1] - hLines[r]
                val cw = vLines[c + 1] - vLines[c]
                val marginPx = max(1, (min(ch, cw) * 0.08).toInt())
                var r1 = hLines[r] + marginPx
                var r2 = hLines[r + 1] - marginPx
                var c1 = vLines[c] + marginPx
                var c2 = vLines[c + 1] - marginPx
                r1 = max(0, min(rows - 1, r1))
                r2 = max(0, min(rows - 1, r2))
                c1 = max(0, min(cols - 1, c1))
                c2 = max(0, min(cols - 1, c2))
                if (r1 > r2) { val t = r1; r1 = r2; r2 = t }
                if (c1 > c2) { val t = c1; c1 = c2; c2 = t }
                cells[r][c] = intArrayOf(r1, r2, c1, c2)
            }
        }
        return cells
    }

    // ============================================================
    // 3. 单元格数字提取
    // ============================================================

    /**
     * 准备单元格图像用于数字识别
     * 返回: Pair(hasDigit, canvas) canvas为32x32 float32 [0,1]
     */
    private fun prepareCell(
        gray: Array<FloatArray>, r1: Int, r2: Int, c1: Int, c2: Int
    ): Pair<Boolean, FloatArray> {
        val ch = r2 - r1 + 1
        val cw = c2 - c1 + 1
        if (ch < 4 || cw < 4) {
            return Pair(false, FloatArray(IMG_SIZE * IMG_SIZE) { 1f })
        }

        // 裁剪边框区域
        val marginPx = max(1, (min(ch, cw) * 0.08).toInt())
        val ir1 = r1 + marginPx
        val ir2 = r2 - marginPx
        val ic1 = c1 + marginPx
        val ic2 = c2 - marginPx

        if (ir1 >= ir2 || ic1 >= ic2) {
            return Pair(false, FloatArray(IMG_SIZE * IMG_SIZE) { 1f })
        }

        // 提取内部区域
        val innerH = ir2 - ir1 + 1
        val innerW = ic2 - ic1 + 1
        val inner = Array(innerH) { FloatArray(innerW) }
        for (i in 0 until innerH) {
            for (j in 0 until innerW) {
                inner[i][j] = gray[ir1 + i][ic1 + j]
            }
        }

        // 墨水检测阈值
        val inkThresh = median(inner) * 0.7f
        var inkCount = 0
        for (i in 0 until innerH) {
            for (j in 0 until innerW) {
                if (inner[i][j] < inkThresh) inkCount++
            }
        }
        val inkRatio = inkCount.toFloat() / (innerH * innerW)
        if (inkRatio < 0.03f) {
            return Pair(false, FloatArray(IMG_SIZE * IMG_SIZE) { 1f })
        }

        // 找墨水边界框
        var inkRMin = innerH; var inkRMax = 0
        var inkCMin = innerW; var inkCMax = 0
        for (i in 0 until innerH) {
            for (j in 0 until innerW) {
                if (inner[i][j] < inkThresh) {
                    if (i < inkRMin) inkRMin = i
                    if (i > inkRMax) inkRMax = i
                    if (j < inkCMin) inkCMin = j
                    if (j > inkCMax) inkCMax = j
                }
            }
        }

        if (inkRMax < inkRMin) {
            return Pair(false, FloatArray(IMG_SIZE * IMG_SIZE) { 1f })
        }

        // 加margin
        val inkH = inkRMax - inkRMin
        val inkW = inkCMax - inkCMin
        val margin = max(2, max(inkH, inkW) / 6)
        val cropR1 = max(0, inkRMin - margin)
        val cropR2 = min(innerH - 1, inkRMax + margin)
        val cropC1 = max(0, inkCMin - margin)
        val cropC2 = min(innerW - 1, inkCMax + margin)

        // 裁剪
        val cropH = cropR2 - cropR1 + 1
        val cropW = cropC2 - cropC1 + 1
        val cropped = Array(cropH) { FloatArray(cropW) }
        for (i in 0 until cropH) {
            for (j in 0 until cropW) {
                cropped[i][j] = inner[cropR1 + i][cropC1 + j]
            }
        }

        // 缩放并居中到32x32白色画布
        val canvas = FloatArray(IMG_SIZE * IMG_SIZE) { 1f }
        val scale = min(
            MAX_DIGIT_SIZE.toFloat() / max(cropH, 1),
            MAX_DIGIT_SIZE.toFloat() / max(cropW, 1)
        )
        val newH = max(1, (cropH * scale).toInt())
        val newW = max(1, (cropW * scale).toInt())

        // 双线性插值缩放
        val resized = bilinearResize(cropped, newW, newH)

        // 居中放置
        val offR = (IMG_SIZE - newH) / 2
        val offC = (IMG_SIZE - newW) / 2
        for (i in 0 until newH) {
            for (j in 0 until newW) {
                canvas[(offR + i) * IMG_SIZE + (offC + j)] = resized[i * newW + j]
            }
        }

        // clip [0, 1]
        for (i in canvas.indices) {
            canvas[i] = canvas[i].coerceIn(0f, 1f)
        }
        return Pair(true, canvas)
    }

    /**
     * 双线性插值缩放
     */
    private fun bilinearResize(src: Array<FloatArray>, newW: Int, newH: Int): FloatArray {
        val srcH = src.size
        val srcW = src[0].size
        val result = FloatArray(newH * newW)

        val xRatio = (srcW - 1).toFloat() / max(newW - 1, 1)
        val yRatio = (srcH - 1).toFloat() / max(newH - 1, 1)

        for (i in 0 until newH) {
            val srcY = i * yRatio
            val y0 = srcY.toInt()
            val y1 = min(y0 + 1, srcH - 1)
            val dy = srcY - y0

            for (j in 0 until newW) {
                val srcX = j * xRatio
                val x0 = srcX.toInt()
                val x1 = min(x0 + 1, srcW - 1)
                val dx = srcX - x0

                val v00 = src[y0][x0]
                val v01 = src[y0][x1]
                val v10 = src[y1][x0]
                val v11 = src[y1][x1]

                val top = v00 * (1 - dx) + v01 * dx
                val bottom = v10 * (1 - dx) + v11 * dx
                result[i * newW + j] = top * (1 - dy) + bottom * dy
            }
        }
        return result
    }

    // ============================================================
    // 4. ONNX推理
    // ============================================================

    /**
     * Softmax
     */
    private fun softmax(x: FloatArray): FloatArray {
        val maxVal = x.maxOrNull() ?: 0f
        val exp = FloatArray(x.size) { kotlin.math.exp(x[it] - maxVal) }
        val sum = exp.sum()
        return FloatArray(x.size) { exp[it] / sum }
    }

    /**
     * 批量预测
     * @param cellImgs list of 32*32 float32 (flattened)
     * @return list of (digit, confidence)
     */
    private fun predictBatch(cellImgs: List<FloatArray>): List<Pair<Int, Float>> {
        if (cellImgs.isEmpty()) return emptyList()

        val batchSize = cellImgs.size
        // 构建输入张量 [batch, 1, 32, 32]
        val inputData = FloatArray(batchSize * 1 * IMG_SIZE * IMG_SIZE)
        for (i in cellImgs.indices) {
            System.arraycopy(cellImgs[i], 0, inputData, i * IMG_SIZE * IMG_SIZE, IMG_SIZE * IMG_SIZE)
        }

        val shape = longArrayOf(batchSize.toLong(), 1, IMG_SIZE.toLong(), IMG_SIZE.toLong())
        val inputTensor = OnnxTensor.createTensor(env!!, inputData, shape)
        val output = session.run(mapOf(inputName to inputTensor))
        val rawOutput = output.get(0).value as Array<FloatArray>

        val results = mutableListOf<Pair<Int, Float>>()
        for (i in 0 until batchSize) {
            val probs = softmax(rawOutput[i])
            var maxIdx = 0
            var maxVal = 0f
            for (j in probs.indices) {
                if (probs[j] > maxVal) {
                    maxVal = probs[j]
                    maxIdx = j
                }
            }
            results.add(Pair(maxIdx, maxVal))
        }

        inputTensor.close()
        output.close()
        return results
    }

    /**
     * 释放资源
     */
    fun close() {
        session.close()
    }
}