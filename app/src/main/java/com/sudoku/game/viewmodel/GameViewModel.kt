package com.sudoku.game.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sudoku.game.engine.SudokuGenerator
import com.sudoku.game.engine.SudokuSolver
import com.sudoku.game.model.Difficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 游戏状态数据
 */
data class GameState(
    val board: Array<IntArray>,
    val given: Array<BooleanArray>,
    val notes: Array<MutableSet<Int>>,
    val selectedCell: Pair<Int, Int>?,
    val selectedNumber: Int,
    val errorCells: Set<Pair<Int, Int>>,
    val hintCell: Pair<Int, Int>?,
    val remainingCounts: IntArray,
    val isCompleted: Boolean,
    val isNoteMode: Boolean,
    val mistakeCount: Int,
    val difficulty: Difficulty
)

/**
 * 游戏ViewModel
 *
 * 管理数独游戏的完整状态和逻辑：
 *   - 新游戏生成（根据难度随机出题 + 可解性验证 + 难度验证）
 *   - 单元格选择与数字输入
 *   - 相同数字高亮
 *   - 候选栏剩余数字数量统计
 *   - 笔记模式（候选数字标记）
 *   - 提示功能（基于求解算法）
 *   - 自动解题（基于项目回溯+MRV剪枝算法）
 *   - 错误检测
 *   - 计时器
 */
class GameViewModel : ViewModel() {

    private val solver = SudokuSolver
    private val generator = SudokuGenerator()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 完整解（用于提示和验证）
    private var solution: Array<IntArray>? = null

    // ---- LiveData ----
    private val _board = MutableLiveData(Array(9) { IntArray(9) })
    val board: LiveData<Array<IntArray>> = _board

    private val _given = MutableLiveData(Array(9) { BooleanArray(9) })
    val given: LiveData<Array<BooleanArray>> = _given

    private val _notes = MutableLiveData<Array<MutableSet<Int>>>(Array(81) { mutableSetOf() })
    val notes: LiveData<Array<MutableSet<Int>>> = _notes

    private val _selectedCell = MutableLiveData<Pair<Int, Int>?>(null)
    val selectedCell: LiveData<Pair<Int, Int>?> = _selectedCell

    private val _selectedNumber = MutableLiveData(0)
    val selectedNumber: LiveData<Int> = _selectedNumber

    private val _errorCells = MutableLiveData<Set<Pair<Int, Int>>>(emptySet())
    val errorCells: LiveData<Set<Pair<Int, Int>>> = _errorCells

    private val _hintCell = MutableLiveData<Pair<Int, Int>?>(null)
    val hintCell: LiveData<Pair<Int, Int>?> = _hintCell

    private val _remainingCounts = MutableLiveData(IntArray(9) { 9 })
    val remainingCounts: LiveData<IntArray> = _remainingCounts

    private val _isCompleted = MutableLiveData(false)
    val isCompleted: LiveData<Boolean> = _isCompleted

    private val _isNoteMode = MutableLiveData(false)
    val isNoteMode: LiveData<Boolean> = _isNoteMode

    private val _mistakeCount = MutableLiveData(0)
    val mistakeCount: LiveData<Int> = _mistakeCount

    private val _difficulty = MutableLiveData(Difficulty.MEDIUM)
    val difficulty: LiveData<Difficulty> = _difficulty

    private val _isGenerating = MutableLiveData(false)
    val isGenerating: LiveData<Boolean> = _isGenerating

    private val _isSolving = MutableLiveData(false)
    val isSolving: LiveData<Boolean> = _isSolving

    // 计时器
    private val _elapsedSeconds = MutableLiveData(0)
    val elapsedSeconds: LiveData<Int> = _elapsedSeconds

    private var timerRunnable: Runnable? = null
    private var timerStarted = false

    // ---- 游戏逻辑 ----

    /**
     * 开始新游戏
     * 在后台线程生成数独，确保UI不卡顿
     */
    fun newGame(difficulty: Difficulty) {
        _isGenerating.value = true
        _difficulty.value = difficulty
        stopTimer()

        viewModelScope.launch(Dispatchers.IO) {
            // 生成并验证难度的数独题目
            val puzzle = generator.generateVerified(difficulty)

            // 求解以获取完整解（用于提示和验证）
            val result = solver.solve(puzzle)
            val sol = if (result.success) result.board else null

            withContext(Dispatchers.Main) {
                solution = sol

                _board.value = puzzle
                _given.value = Array(9) { r -> BooleanArray(9) { c -> puzzle[r][c] != 0 } }
                _notes.value = Array(81) { mutableSetOf() }
                _selectedCell.value = null
                _selectedNumber.value = 0
                _errorCells.value = emptySet()
                _hintCell.value = null
                _isCompleted.value = false
                _isNoteMode.value = false
                _mistakeCount.value = 0
                _elapsedSeconds.value = 0
                _isGenerating.value = false

                updateRemainingCounts(puzzle)
                startTimer()
            }
        }
    }

    /**
     * 选择单元格
     */
    fun selectCell(row: Int, col: Int) {
        _selectedCell.value = Pair(row, col)
        val value = _board.value?.get(row)?.get(col) ?: 0
        _selectedNumber.value = value
        // 清除上一次的提示高亮
        _hintCell.value = null
    }

    /**
     * 输入数字到选中的单元格
     */
    fun inputNumber(num: Int) {
        if (_isCompleted.value == true) return

        val cell = _selectedCell.value ?: return
        val (r, c) = cell
        val givens = _given.value ?: return
        if (givens[r][c]) return // 题目给定的数字不能修改

        if (_isNoteMode.value == true) {
            // 笔记模式：切换候选数字标记
            toggleNote(r, c, num)
        } else {
            // 正常模式：填入数字
            val b = _board.value ?: return
            val currentValue = b[r][c]

            // 再次点击相同数字则清除
            b[r][c] = if (currentValue == num) 0 else num
            _board.value = b

            // 清除该格的笔记
            _notes.value?.get(r * 9 + c)?.clear()
            _notes.value = _notes.value

            // 更新选中的数字
            _selectedNumber.value = b[r][c]

            // 检查错误
            checkErrors()

            // 更新剩余数量
            updateRemainingCounts(b)

            // 检查是否完成
            checkCompletion()
        }
    }

    /**
     * 擦除选中单元格的数字
     */
    fun erase() {
        if (_isCompleted.value == true) return

        val cell = _selectedCell.value ?: return
        val (r, c) = cell
        val givens = _given.value ?: return
        if (givens[r][c]) return

        val b = _board.value ?: return
        b[r][c] = 0
        _board.value = b

        // 清除笔记
        _notes.value?.get(r * 9 + c)?.clear()
        _notes.value = _notes.value

        _selectedNumber.value = 0

        checkErrors()
        updateRemainingCounts(b)
    }

    /**
     * 切换笔记模式
     */
    fun toggleNoteMode() {
        _isNoteMode.value = !(_isNoteMode.value ?: false)
    }

    /**
     * 提示功能
     * 使用求解算法找到正确答案并填入
     *
     * 策略：
     *   - 如果选中了空格，填入该格的正确答案
     *   - 如果没有选中空格，找到一个空格并填入
     */
    fun hint() {
        if (_isCompleted.value == true) return
        val sol = solution ?: return
        val b = _board.value ?: return

        val cell = _selectedCell.value
        if (cell != null) {
            val (r, c) = cell
            if (_given.value?.get(r)?.get(c) == true) return
            if (b[r][c] == sol[r][c]) {
                // 已正确，找下一个空格
                findAndHintEmpty(b, sol)
            } else {
                // 填入正确答案
                applyHint(r, c, sol[r][c], b)
            }
        } else {
            findAndHintEmpty(b, sol)
        }
    }

    private fun findAndHintEmpty(b: Array<IntArray>, sol: Array<IntArray>) {
        for (r in 0..8) {
            for (c in 0..8) {
                if (b[r][c] != sol[r][c]) {
                    applyHint(r, c, sol[r][c], b)
                    return
                }
            }
        }
    }

    private fun applyHint(r: Int, c: Int, value: Int, b: Array<IntArray>) {
        b[r][c] = value
        _board.value = b
        _notes.value?.get(r * 9 + c)?.clear()
        _notes.value = _notes.value
        _selectedCell.value = Pair(r, c)
        _selectedNumber.value = value
        _hintCell.value = Pair(r, c)
        updateRemainingCounts(b)
        checkErrors()
        checkCompletion()
    }

    /**
     * 自动解题
     * 使用项目的回溯+MRV剪枝算法求解，逐步填入
     */
    fun solveAll() {
        if (_isCompleted.value == true) return
        val sol = solution
        val b = _board.value ?: return

        if (sol != null) {
            _isSolving.value = true
            // 逐步填入未填的格子，产生动画效果
            val cellsToFill = mutableListOf<Triple<Int, Int, Int>>()
            for (r in 0..8) {
                for (c in 0..8) {
                    if (b[r][c] != sol[r][c]) {
                        cellsToFill.add(Triple(r, c, sol[r][c]))
                    }
                }
            }

            if (cellsToFill.isEmpty()) {
                _isSolving.value = false
                return
            }

            // 逐步填入，每50ms填一个
            var index = 0
            val fillNext = object : Runnable {
                override fun run() {
                    if (index >= cellsToFill.size) {
                        _isSolving.value = false
                        _isCompleted.value = true
                        _given.value = Array(9) { r -> BooleanArray(9) { c -> true } }
                        stopTimer()
                        return
                    }
                    val (r, c, v) = cellsToFill[index]
                    b[r][c] = v
                    _board.value = b
                    _selectedCell.value = Pair(r, c)
                    _selectedNumber.value = v
                    _hintCell.value = Pair(r, c)
                    updateRemainingCounts(b)
                    index++
                    mainHandler.postDelayed(this, 50)
                }
            }
            fillNext.run()
        } else {
            // 没有缓存解，现场求解
            _isSolving.value = true
            viewModelScope.launch(Dispatchers.IO) {
                val result = solver.solve(b)
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        solution = result.board
                        _board.value = result.board
                        _given.value = Array(9) { r -> BooleanArray(9) { c -> true } }
                        _isCompleted.value = true
                        _selectedCell.value = null
                        _selectedNumber.value = 0
                        _hintCell.value = null
                        updateRemainingCounts(result.board)
                        stopTimer()
                    }
                    _isSolving.value = false
                }
            }
        }
    }

    /**
     * 检查当前棋盘是否有错误
     * 错误定义：与正确解不一致的用户输入
     */
    private fun checkErrors() {
        val sol = solution ?: return
        val b = _board.value ?: return
        val givens = _given.value ?: return

        val errors = mutableSetOf<Pair<Int, Int>>()
        for (r in 0..8) {
            for (c in 0..8) {
                if (!givens[r][c] && b[r][c] != 0 && b[r][c] != sol[r][c]) {
                    errors.add(Pair(r, c))
                }
            }
        }

        // 统计错误数（新增的错误）
        val prevErrors = _errorCells.value ?: emptySet()
        val newErrors = errors - prevErrors
        if (newErrors.isNotEmpty()) {
            _mistakeCount.value = (_mistakeCount.value ?: 0) + newErrors.size
        }

        _errorCells.value = errors
    }

    /**
     * 更新剩余数字数量
     * 每个数字1-9在数独中出现9次，remaining = 9 - 已使用次数
     */
    private fun updateRemainingCounts(b: Array<IntArray>) {
        val counts = IntArray(10) // counts[0] 不使用
        for (r in 0..8) {
            for (c in 0..8) {
                if (b[r][c] != 0) counts[b[r][c]]++
            }
        }
        val remaining = IntArray(9) { i -> 9 - counts[i + 1] }
        _remainingCounts.value = remaining
    }

    /**
     * 检查是否完成
     */
    private fun checkCompletion() {
        val b = _board.value ?: return
        for (r in 0..8) {
            for (c in 0..8) {
                if (b[r][c] == 0) return
            }
        }
        // 所有格子都填了，检查是否正确
        val sol = solution
        if (sol != null) {
            for (r in 0..8) {
                for (c in 0..8) {
                    if (b[r][c] != sol[r][c]) return
                }
            }
        }
        _isCompleted.value = true
        _selectedCell.value = null
        _selectedNumber.value = 0
        _hintCell.value = null
        stopTimer()
    }

    /**
     * 切换笔记标记
     */
    private fun toggleNote(r: Int, c: Int, num: Int) {
        val currentNotes = _notes.value ?: return
        val cellNotes = currentNotes[r * 9 + c]
        if (cellNotes.contains(num)) {
            cellNotes.remove(num)
        } else {
            cellNotes.add(num)
        }
        _notes.value = currentNotes
    }

    // ---- 计时器 ----

    private fun startTimer() {
        if (timerStarted) return
        timerStarted = true
        timerRunnable = object : Runnable {
            override fun run() {
                _elapsedSeconds.value = (_elapsedSeconds.value ?: 0) + 1
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.postDelayed(timerRunnable!!, 1000)
    }

    private fun stopTimer() {
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        timerRunnable = null
        timerStarted = false
    }

    override fun onCleared() {
        super.onCleared()
        stopTimer()
    }
}
