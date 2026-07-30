package com.sudoku.game.viewmodel

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.sudoku.game.engine.SudokuGenerator
import com.sudoku.game.engine.SudokuSolver
import com.sudoku.game.model.Difficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * GameViewModel - 数独游戏核心状态管理
 *
 * 设计原则：
 *   - 不预存答案（solution），提示和自动解题均通过实时求解器计算
 *   - 错误检测基于数独规则（行/列/宫冲突），而非对比预存答案
 *   - 每次填入产生冲突的数字计1次错误（不论冲突多少格），累计3次游戏结束
 *   - 支持存档/读档（SharedPreferences + JSON）
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val solver = SudokuSolver
    private val generator = SudokuGenerator()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val MAX_MISTAKES = 3
        private const val PREFS_NAME = "sudoku_save"
        private const val KEY_SAVED_GAME = "saved_game"
    }

    // ---- LiveData ----
    private val _board = MutableLiveData(Array(9) { IntArray(9) })
    val board: LiveData<Array<IntArray>> = _board

    private val _given = MutableLiveData(Array(9) { BooleanArray(9) })
    val given: LiveData<Array<BooleanArray>> = _given

    private val _notes = MutableLiveData(Array(81) { mutableSetOf<Int>() })
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

    private val _isGameOver = MutableLiveData(false)
    val isGameOver: LiveData<Boolean> = _isGameOver

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

    private val _isHinting = MutableLiveData(false)
    val isHinting: LiveData<Boolean> = _isHinting

    // Timer
    private val _elapsedSeconds = MutableLiveData(0)
    val elapsedSeconds: LiveData<Int> = _elapsedSeconds

    private var timerRunnable: Runnable? = null
    private var timerStarted = false
    private var gameActive = false
    private var isPaused = false

    // ================================================================
    // 暂停 / 恢复
    // ================================================================

    /**
     * 暂停游戏：停止计时器，禁止用户操作
     */
    fun pauseGame() {
        if (!gameActive || _isCompleted.value == true || _isGameOver.value == true) return
        isPaused = true
        stopTimer()
    }

    /**
     * 恢复游戏：重新开始计时器
     */
    fun resumeGame() {
        if (!isPaused) return
        isPaused = false
        startTimer()
    }

    fun isPaused(): Boolean = isPaused

    // ================================================================
    // 新游戏 / 读档 / 加载识别棋盘
    // ================================================================

    /**
     * 开始新游戏 - 根据难度生成谜题，不预存答案
     */
    fun newGame(difficulty: Difficulty) {
        _isGenerating.value = true
        _isGameOver.value = false
        isPaused = false
        _difficulty.value = difficulty
        stopTimer()

        viewModelScope.launch(Dispatchers.IO) {
            val puzzle = generator.generateVerified(difficulty)

            withContext(Dispatchers.Main) {
                resetBoardState(puzzle)
                _isGenerating.value = false
                gameActive = true
                startTimer()
            }
        }
    }

    /**
     * 从识别结果加载数独棋盘，不预存答案
     */
    fun loadBoard(puzzle: Array<IntArray>) {
        _isGenerating.value = true
        _isGameOver.value = false
        isPaused = false
        stopTimer()

        viewModelScope.launch(Dispatchers.Main) {
            resetBoardState(puzzle)
            _isGenerating.value = false
            gameActive = true
            startTimer()
        }
    }

    /**
     * 从存档恢复游戏
     */
    fun loadSavedGame(): Boolean {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SAVED_GAME, null) ?: return false

        return try {
            val obj = JSONObject(json)
            val board = jsonToBoard(obj.getJSONArray("board"))
            val given = jsonToGiven(obj.getJSONArray("given"))
            val mistakes = obj.getInt("mistakes")
            val elapsed = obj.getInt("elapsed")
            val diff = Difficulty.fromLabel(obj.getString("difficulty"))

            _difficulty.value = diff
            _isGameOver.value = false
            stopTimer()

            _board.value = board
            _given.value = given
            _notes.value = Array(81) { mutableSetOf<Int>() }
            _selectedCell.value = null
            _selectedNumber.value = 0
            _errorCells.value = detectConflicts(board)
            _hintCell.value = null
            _isCompleted.value = false
            _isNoteMode.value = false
            _mistakeCount.value = mistakes
            _elapsedSeconds.value = elapsed
            _isGenerating.value = false

            updateRemainingCounts(board)
            gameActive = true
            startTimer()

            prefs.edit().remove(KEY_SAVED_GAME).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 保存当前游戏状态
     */
    fun saveGame() {
        if (!gameActive || _isCompleted.value == true || _isGameOver.value == true) return

        val b = _board.value ?: return
        val g = _given.value ?: return

        val obj = JSONObject()
        obj.put("board", boardToJson(b))
        obj.put("given", givenToJson(g))
        obj.put("mistakes", _mistakeCount.value ?: 0)
        obj.put("elapsed", _elapsedSeconds.value ?: 0)
        obj.put("difficulty", _difficulty.value?.label ?: Difficulty.MEDIUM.label)

        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SAVED_GAME, obj.toString()).apply()
    }

    fun hasSavedGame(): Boolean {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_SAVED_GAME)
    }

    private fun resetBoardState(puzzle: Array<IntArray>) {
        _board.value = puzzle
        _given.value = Array(9) { r -> BooleanArray(9) { c -> puzzle[r][c] != 0 } }
        _notes.value = Array(81) { mutableSetOf<Int>() }
        _selectedCell.value = null
        _selectedNumber.value = 0
        _errorCells.value = emptySet()
        _hintCell.value = null
        _isCompleted.value = false
        _isNoteMode.value = false
        _mistakeCount.value = 0
        _elapsedSeconds.value = 0
        updateRemainingCounts(puzzle)
    }

    // ================================================================
    // 用户操作
    // ================================================================

    fun selectCell(row: Int, col: Int) {
        if (_isCompleted.value == true || _isGameOver.value == true || isPaused) return
        _selectedCell.value = Pair(row, col)
        val value = _board.value?.get(row)?.get(col) ?: 0
        _selectedNumber.value = value
        _hintCell.value = null
    }

    fun inputNumber(num: Int) {
        if (_isCompleted.value == true || _isGameOver.value == true || isPaused) return

        val cell = _selectedCell.value ?: return
        val (r, c) = cell
        val givens = _given.value ?: return
        if (givens[r][c]) return

        if (_isNoteMode.value == true) {
            toggleNote(r, c, num)
        } else {
            val b = _board.value ?: return
            val currentValue = b[r][c]

            b[r][c] = if (currentValue == num) 0 else num
            _board.value = b

            _notes.value?.get(r * 9 + c)?.clear()
            _notes.value = _notes.value
            _selectedNumber.value = b[r][c]

            // 只检查用户刚填入的格子是否产生冲突
            checkMistakes(b, r, c)
            _errorCells.value = detectConflicts(b)
            updateRemainingCounts(b)
            checkCompletion(b)
        }
    }

    fun erase() {
        if (_isCompleted.value == true || _isGameOver.value == true) return

        val cell = _selectedCell.value ?: return
        val (r, c) = cell
        val givens = _given.value ?: return
        if (givens[r][c]) return

        val b = _board.value ?: return
        b[r][c] = 0
        _board.value = b

        _notes.value?.get(r * 9 + c)?.clear()
        _notes.value = _notes.value
        _selectedNumber.value = 0

        _errorCells.value = detectConflicts(b)
        updateRemainingCounts(b)
    }

    fun toggleNoteMode() {
        _isNoteMode.value = !(_isNoteMode.value ?: false)
    }

    // ================================================================
    // 提示功能 - 实时求解，不使用预存答案
    // ================================================================

    /**
     * 提示：实时求解当前棋盘，填充一个正确答案
     * 若当前棋盘因用户错误无解，则从原始题目（given格）重新求解
     */
    fun hint() {
        if (_isCompleted.value == true || _isGameOver.value == true) return
        if (_isHinting.value == true || _isSolving.value == true) return

        val b = _board.value ?: return
        val givens = _given.value ?: return

        _isHinting.value = true

        viewModelScope.launch(Dispatchers.IO) {
            // 尝试求解当前棋盘
            var result = solver.solve(b)

            // 若当前棋盘无解（用户填入了错误数字），从原始题目求解
            if (!result.success) {
                val originalBoard = Array(9) { IntArray(9) }
                for (r in 0..8) {
                    for (c in 0..8) {
                        originalBoard[r][c] = if (givens[r][c]) b[r][c] else 0
                    }
                }
                result = solver.solve(originalBoard)
            }

            withContext(Dispatchers.Main) {
                _isHinting.value = false

                if (!result.success) return@withContext

                val sol = result.board
                val cell = _selectedCell.value
                var filled = false

                if (cell != null) {
                    val (r, c) = cell
                    if (!givens[r][c] && b[r][c] != sol[r][c]) {
                        applyHint(r, c, sol[r][c], b)
                        filled = true
                    }
                }

                if (!filled) {
                    val startR = _selectedCell.value?.first ?: 0
                    val startC = _selectedCell.value?.second ?: 0
                    for (offset in 0..80) {
                        val idx = (startR * 9 + startC + offset) % 81
                        val r = idx / 9
                        val c = idx % 9
                        if (!givens[r][c] && b[r][c] != sol[r][c]) {
                            applyHint(r, c, sol[r][c], b)
                            return@withContext
                        }
                    }
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
        _errorCells.value = detectConflicts(b)
        checkCompletion(b)
    }

    // ================================================================
    // 自动解题 - 实时求解，不使用预存答案
    // ================================================================

    /**
     * 自动解题：实时求解当前棋盘，逐格动画填充
     * 若当前棋盘因用户错误无解，则从原始题目（given格）重新求解
     */
    fun solveAll() {
        if (_isCompleted.value == true || _isGameOver.value == true) return
        if (_isSolving.value == true) return

        val b = _board.value ?: return
        val givens = _given.value ?: return
        _isSolving.value = true

        viewModelScope.launch(Dispatchers.IO) {
            // 尝试求解当前棋盘
            var result = solver.solve(b)

            // 若当前棋盘无解，从原始题目求解
            if (!result.success) {
                val originalBoard = Array(9) { IntArray(9) }
                for (r in 0..8) {
                    for (c in 0..8) {
                        originalBoard[r][c] = if (givens[r][c]) b[r][c] else 0
                    }
                }
                result = solver.solve(originalBoard)
            }

            val sol = if (result.success) result.board else null

            withContext(Dispatchers.Main) {
                if (sol == null) {
                    _isSolving.value = false
                    return@withContext
                }

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
                    _isCompleted.value = true
                    stopTimer()
                    return@withContext
                }

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
            }
        }
    }

    // ================================================================
    // 错误检测 - 基于数独规则（行/列/宫冲突）
    // ================================================================

    /**
     * 检测棋盘上的所有冲突单元格
     */
    private fun detectConflicts(b: Array<IntArray>): Set<Pair<Int, Int>> {
        val conflicts = mutableSetOf<Pair<Int, Int>>()

        for (r in 0..8) {
            for (c in 0..8) {
                val v = b[r][c]
                if (v == 0) continue

                for (cc in 0..8) {
                    if (cc != c && b[r][cc] == v) {
                        conflicts.add(Pair(r, c))
                        conflicts.add(Pair(r, cc))
                    }
                }
                for (rr in 0..8) {
                    if (rr != r && b[rr][c] == v) {
                        conflicts.add(Pair(r, c))
                        conflicts.add(Pair(rr, c))
                    }
                }
                val br = (r / 3) * 3
                val bc = (c / 3) * 3
                for (rr in br until br + 3) {
                    for (cc in bc until bc + 3) {
                        if ((rr != r || cc != c) && b[rr][cc] == v) {
                            conflicts.add(Pair(r, c))
                            conflicts.add(Pair(rr, cc))
                        }
                    }
                }
            }
        }
        return conflicts
    }

    /**
     * 检查用户刚填入的格子是否产生新的冲突
     * 每次填入产生冲突计1次错误（不论与多少格冲突）
     */
    private fun checkMistakes(b: Array<IntArray>, placedRow: Int, placedCol: Int) {
        val givens = _given.value ?: return
        if (givens[placedRow][placedCol]) return

        val prevErrors = _errorCells.value ?: emptySet()
        val currentErrors = detectConflicts(b)

        val cellKey = Pair(placedRow, placedCol)
        val cellWasError = prevErrors.contains(cellKey)
        val cellIsError = currentErrors.contains(cellKey)

        // 仅当该格子新产生冲突（之前不冲突，现在冲突）时计1次错误
        if (cellIsError && !cellWasError) {
            val newCount = (_mistakeCount.value ?: 0) + 1
            _mistakeCount.value = newCount

            if (newCount >= MAX_MISTAKES) {
                _isGameOver.value = true
                stopTimer()
            }
        }
    }

    /**
     * 检查是否完成（所有格子已填且无冲突）
     */
    private fun checkCompletion(b: Array<IntArray>) {
        for (r in 0..8) {
            for (c in 0..8) {
                if (b[r][c] == 0) return
            }
        }
        if (detectConflicts(b).isNotEmpty()) return

        _isCompleted.value = true
        _selectedCell.value = null
        _selectedNumber.value = 0
        _hintCell.value = null
        stopTimer()
    }

    private fun updateRemainingCounts(b: Array<IntArray>) {
        val counts = IntArray(10)
        for (r in 0..8) {
            for (c in 0..8) {
                if (b[r][c] != 0) counts[b[r][c]]++
            }
        }
        val remaining = IntArray(9) { i -> 9 - counts[i + 1] }
        _remainingCounts.value = remaining
    }

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

    // ================================================================
    // 存档序列化/反序列化
    // ================================================================

    private fun boardToJson(b: Array<IntArray>): JSONArray {
        val arr = JSONArray()
        for (r in 0..8) {
            val row = JSONArray()
            for (c in 0..8) row.put(b[r][c])
            arr.put(row)
        }
        return arr
    }

    private fun givenToJson(g: Array<BooleanArray>): JSONArray {
        val arr = JSONArray()
        for (r in 0..8) {
            val row = JSONArray()
            for (c in 0..8) row.put(g[r][c])
            arr.put(row)
        }
        return arr
    }

    private fun jsonToBoard(arr: JSONArray): Array<IntArray> {
        val b = Array(9) { IntArray(9) }
        for (r in 0..8) {
            val row = arr.getJSONArray(r)
            for (c in 0..8) b[r][c] = row.getInt(c)
        }
        return b
    }

    private fun jsonToGiven(arr: JSONArray): Array<BooleanArray> {
        val g = Array(9) { BooleanArray(9) }
        for (r in 0..8) {
            val row = arr.getJSONArray(r)
            for (c in 0..8) g[r][c] = row.getBoolean(c)
        }
        return g
    }

    // ================================================================
    // Timer
    // ================================================================

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