package com.sudoku.game.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.sudoku.game.R

/**
 * Sudoku board custom view with theme support.
 *
 * Features:
 *   - 9x9 grid with 3x3 box separators
 *   - Given numbers (bold), user input (blue), errors (red)
 *   - Selected cell highlight
 *   - Same row/col/box highlight
 *   - Same number highlight
 *   - Error cell red background
 *   - Hint cell yellow background
 *   - Notes (3x3 mini grid)
 *   - Touch selection
 *   - DayNight theme-aware colors
 */
class SudokuBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- Board data ----
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

    var notes: Array<MutableSet<Int>> = Array(81) { mutableSetOf() }
        set(value) {
            field = value
            invalidate()
        }

    var selectedCell: Pair<Int, Int>? = null
        set(value) {
            field = value
            invalidate()
        }

    var highlightNumber: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var errorCells: Set<Pair<Int, Int>> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    var hintCell: Pair<Int, Int>? = null
        set(value) {
            field = value
            invalidate()
        }

    var onCellSelected: ((row: Int, col: Int) -> Unit)? = null

    // ---- Paints ----
    private val cellBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Theme-aware colors (reloaded on configuration change)
    private val colorCellDefault: Int
    private val colorCellHighlight: Int
    private val colorCellSameNumber: Int
    private val colorCellSelected: Int
    private val colorCellError: Int
    private val colorCellHint: Int
    private val colorLineThin: Int
    private val colorLineThick: Int
    private val colorTextGiven: Int
    private val colorTextUser: Int
    private val colorTextError: Int
    private val colorTextNote: Int

    init {
        linePaint.style = Paint.Style.STROKE
        textPaint.textAlign = Paint.Align.CENTER
        notePaint.textAlign = Paint.Align.CENTER

        // Load colors from resources (automatically switches with DayNight theme)
        colorCellDefault = ContextCompat.getColor(context, R.color.cell_default)
        colorCellHighlight = ContextCompat.getColor(context, R.color.cell_highlight)
        colorCellSameNumber = ContextCompat.getColor(context, R.color.cell_same_number)
        colorCellSelected = ContextCompat.getColor(context, R.color.cell_selected)
        colorCellError = ContextCompat.getColor(context, R.color.cell_error)
        colorCellHint = ContextCompat.getColor(context, R.color.cell_hint)
        colorLineThin = ContextCompat.getColor(context, R.color.grid_line_thin)
        colorLineThick = ContextCompat.getColor(context, R.color.grid_line_thick)
        colorTextGiven = ContextCompat.getColor(context, R.color.text_given)
        colorTextUser = ContextCompat.getColor(context, R.color.text_user)
        colorTextError = ContextCompat.getColor(context, R.color.text_error)
        colorTextNote = ContextCompat.getColor(context, R.color.text_note)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = minOf(measuredWidth, measuredHeight)
        val minSize = 200
        val finalSize = maxOf(size, minSize)
        setMeasuredDimension(finalSize, finalSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cellSize = width.toFloat() / 9f

        // ---- 1. Draw cell backgrounds ----
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

        // ---- 2. Draw grid lines ----
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

        // ---- 3. Draw numbers and notes ----
        for (r in 0..8) {
            for (c in 0..8) {
                val value = board[r][c]
                val cx = c * cellSize + cellSize / 2f
                val cy = r * cellSize + cellSize / 2f

                if (value != 0) {
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