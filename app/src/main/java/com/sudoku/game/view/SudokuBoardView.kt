package com.sudoku.game.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 数独棋盘自定义视图
 *
 * 功能：
 *   - 绘制 9x9 网格，3x3 宫格用粗线分隔
 *   - 绘制数字：题目数字（黑色粗体）、用户输入（蓝色）、错误（红色）
 *   - 选中单元格高亮（蓝色）
 *   - 同行/同列/同宫高亮（浅蓝）
 *   - 相同数字高亮（中蓝）
 *   - 错误单元格红色背景
 *   - 提示单元格黄色背景
 *   - 笔记标记（3x3小数字网格）
 *   - 触摸选择单元格
 */
class SudokuBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- 棋盘数据 ----
    var board: Array<IntArray> = Array(9) { IntArray(9) }
        set(value) {
            field = value
            invalidate()
        }

    var given: Array<BooleanArray> = Array(9) { BooleanArray(9) }
        set(value) {
            field = value
            invalidate()
        }

    /** 笔记标记，notes[r*9+c] 存储该格的候选数字集合 */
    var notes: Array<MutableSet<Int>> = Array(81) { mutableSetOf() }
        set(value) {
            field = value
            invalidate()
        }

    /** 当前选中的单元格 */
    var selectedCell: Pair<Int, Int>? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 高亮的数字（选中单元格的值，用于高亮相同数字） */
    var highlightNumber: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    /** 错误单元格集合 */
    var errorCells: Set<Pair<Int, Int>> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    /** 提示单元格 */
    var hintCell: Pair<Int, Int>? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 单元格点击回调 */
    var onCellSelected: ((row: Int, col: Int) -> Unit)? = null

    // ---- 画笔 ----
    private val cellBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 颜色定义
    private val colorCellDefault = Color.parseColor("#FFFFFF")
    private val colorCellHighlight = Color.parseColor("#E3F2FD")
    private val colorCellSameNumber = Color.parseColor("#BBDEFB")
    private val colorCellSelected = Color.parseColor("#90CAF9")
    private val colorCellError = Color.parseColor("#FFCDD2")
    private val colorCellHint = Color.parseColor("#FFF9C4")
    private val colorLineThin = Color.parseColor("#E0E0E0")
    private val colorLineThick = Color.parseColor("#424242")
    private val colorTextGiven = Color.parseColor("#1A1A1A")
    private val colorTextUser = Color.parseColor("#1565C0")
    private val colorTextError = Color.parseColor("#C62828")
    private val colorTextNote = Color.parseColor("#9E9E9E")

    init {
        linePaint.style = Paint.Style.STROKE
        textPaint.textAlign = Paint.Align.CENTER
        notePaint.textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = minOf(measuredWidth, measuredHeight)
        // 确保最小尺寸
        val minSize = 200
        val finalSize = maxOf(size, minSize)
        setMeasuredDimension(finalSize, finalSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cellSize = width.toFloat() / 9f

        // ---- 1. 绘制单元格背景 ----
        for (r in 0..8) {
            for (c in 0..8) {
                val left = c * cellSize
                val top = r * cellSize
                val rect = RectF(left, top, left + cellSize, top + cellSize)

                val isSelected = selectedCell?.let { it.first == r && it.second == c } ?: false
                val value = board[r][c]
                val sel = selectedCell
                val sameRow = sel?.first == r
                val sameCol = sel?.second == c
                val sameBox = sel?.let { (it.first / 3 == r / 3) && (it.second / 3 == c / 3) } ?: false
                val sameNumber = highlightNumber != 0 && value == highlightNumber
                val isError = Pair(r, c) in errorCells
                val isHint = hintCell?.let { it.first == r && it.second == c } ?: false

                cellBgPaint.color = when {
                    isError -> colorCellError
                    isHint -> colorCellHint
                    isSelected -> colorCellSelected
                    sameNumber -> colorCellSameNumber
                    sameRow || sameCol || sameBox -> colorCellHighlight
                    else -> colorCellDefault
                }
                canvas.drawRect(rect, cellBgPaint)
            }
        }

        // ---- 2. 绘制网格线 ----
        for (i in 0..9) {
            val pos = i * cellSize
            if (i % 3 == 0) {
                linePaint.color = colorLineThick
                linePaint.strokeWidth = 3f
            } else {
                linePaint.color = colorLineThin
                linePaint.strokeWidth = 1f
            }
            canvas.drawLine(pos, 0f, pos, height.toFloat(), linePaint)
            canvas.drawLine(0f, pos, width.toFloat(), pos, linePaint)
        }

        // ---- 3. 绘制数字和笔记 ----
        for (r in 0..8) {
            for (c in 0..8) {
                val value = board[r][c]
                val cx = c * cellSize + cellSize / 2f
                val cy = r * cellSize + cellSize / 2f

                if (value != 0) {
                    // 绘制数字
                    textPaint.textSize = cellSize * 0.5f
                    textPaint.color = when {
                        Pair(r, c) in errorCells -> colorTextError
                        given[r][c] -> colorTextGiven
                        else -> colorTextUser
                    }
                    textPaint.isFakeBoldText = given[r][c]
                    val fm = textPaint.fontMetrics
                    val baseline = cy - (fm.descent + fm.ascent) / 2f
                    canvas.drawText(value.toString(), cx, baseline, textPaint)
                } else if (notes[r * 9 + c].isNotEmpty()) {
                    // 绘制笔记（3x3 小数字网格）
                    notePaint.textSize = cellSize * 0.2f
                    notePaint.color = colorTextNote
                    for (n in notes[r * 9 + c]) {
                        val nr = (n - 1) / 3
                        val nc = (n - 1) % 3
                        val nx = c * cellSize + cellSize * (nc + 0.5f) / 3f
                        val ny = r * cellSize + cellSize * (nr + 0.5f) / 3f
                        val fm = notePaint.fontMetrics
                        val baseline = ny - (fm.descent + fm.ascent) / 2f
                        canvas.drawText(n.toString(), nx, baseline, notePaint)
                    }
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val cellSize = width.toFloat() / 9f
            val c = (event.x / cellSize).toInt().coerceIn(0, 8)
            val r = (event.y / cellSize).toInt().coerceIn(0, 8)
            selectedCell = Pair(r, c)
            onCellSelected?.invoke(r, c)
            return true
        }
        return super.onTouchEvent(event)
    }
}
