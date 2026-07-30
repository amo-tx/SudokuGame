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
 * Game state data
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
 * Game ViewModel
 *
 * Manages the complete state and logic of the Sudoku game:
 *   - New game generation (difficulty-based + solvability verification)
 *   - Cell selection and number input
 *   - Same number highlighting
 *   - Remaining count in number pad
 *   - Note mode (candidate marking)
 *   - Hint function (based on solver)
 *   - Auto-solving (backtracking + MRV heuristic)
 *   - Error detection
 *   - Timer
 */
class GameViewModel : ViewModel() {

    private val solver = SudokuSolver
    private val generator = SudokuGenerator()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Full solution (for hints and verification)
    private var solution: Array<IntArray>? = null

    // ---- LiveData ----
    private val _board = MutableLiveData(Array(9) { IntArray(9) })
    val board: LiveData<Array<IntArray>> = _board

    private val _given = MutableLiveData(Array(9) { BooleanArray(9) })
    val given: LiveData<Array<BooleanArray>> = _given

    private val _notes = MutableLiveData(Array(81) { mutableSetOf() })
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

    // Timer
    private val _elapsedSeconds = MutableLiveData(0)
    val elapsedSeconds: LiveData<Int> = _elapsedSeconds

    private var timerRunnable: Runnable? = null
    private var timerStarted = false

    // ---- Game logic ----

    /**
     * Start a new game
     * Generates a verified Sudoku puzzle on a background thread
     */
    fun newGame(difficulty: Difficulty) {
        _isGenerating.value = true
        _difficulty.value = difficulty
        stopTimer()

        viewModelScope.launch(Dispatchers.IO) {
            val puzzle = generator.generateVerified(difficulty)
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
     * Select a cell
     */
    fun selectCell(row: Int, col: Int) {
        _selectedCell.value = Pair(row, col)
        val value = _board.value?.get(row)?.get(col) ?: 0
        _selectedNumber.value = value
        // Clear previous hint highlight
        _hintCell.value = null
    }

    /**
     * Input a number into the selected cell
     */
    fun inputNumber(num: Int) {
        if (_isCompleted.value == true) return

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

            checkErrors()
            updateRemainingCounts(b)
            checkCompletion()
        }
    }

    /**
     * Erase the selected cell's value
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

        _notes.value?.get(r * 9 + c)?.clear()
        _notes.value = _notes.value

        _selectedNumber.value = 0

        checkErrors()
        updateRemainingCounts(b)
    }

    /**
     * Toggle note mode
     */
    fun toggleNoteMode() {
        _isNoteMode.value = !(_isNoteMode.value ?: false)
    }

    /**
     * Hint function - fills in a correct answer using the solver.
     *
     * Strategy:
     *   - If a non-given cell is selected and it's wrong/empty, fill it with the correct answer
     *   - If the selected cell is given or already correct, find the next empty/wrong cell
     *   - If no cell is selected, find the first empty/wrong cell
     *   - Prioritizes the selected cell, then scans row-by-row
     */
    fun hint() {
        if (_isCompleted.value == true) return
        val sol = solution ?: return
        val b = _board.value ?: return
        val givens = _given.value ?: return

        val cell = _selectedCell.value
        if (cell != null) {
            val (r, c) = cell
            if (!givens[r][c] && b[r][c] != sol[r][c]) {
                // Selected cell is empty or wrong -> fill with correct answer
                applyHint(r, c, sol[r][c], b)
                return
            }
        }
        // Selected cell is given, correct, or none selected -> find next empty/wrong cell
        findAndHintEmpty(b, sol)
    }

    /**
     * Find the next empty or incorrect cell and fill it with the correct answer.
     * Searches from the selected cell's position first, then wraps around.
     */
    private fun findAndHintEmpty(b: Array<IntArray>, sol: Array<IntArray>) {
        val givens = _given.value ?: return
        val startR = _selectedCell.value?.first ?: 0
        val startC = _selectedCell.value?.second ?: 0

        // Search from the selected position forward, then wrap around
        for (offset in 0..80) {
            val idx = (startR * 9 + startC + offset) % 81
            val r = idx / 9
            val c = idx % 9
            if (!givens[r][c] && b[r][c] != sol[r][c]) {
                applyHint(r, c, sol[r][c], b)
                return
            }
        }
    }

    /**
     * Apply a hint: fill the cell with the correct value and update all state
     */
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
     * Auto-solve using backtracking + MRV, filling cells one by one with animation
     */
    fun solveAll() {
        if (_isCompleted.value == true) return
        val sol = solution
        val b = _board.value ?: return

        if (sol != null) {
            _isSolving.value = true
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
     * Check the board for errors (user input that doesn't match the solution)
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

        val prevErrors = _errorCells.value ?: emptySet()
        val newErrors = errors - prevErrors
        if (newErrors.isNotEmpty()) {
            _mistakeCount.value = (_mistakeCount.value ?: 0) + newErrors.size
        }

        _errorCells.value = errors
    }

    /**
     * Update remaining count for each number (1-9)
     */
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

    /**
     * Check if the puzzle is complete
     */
    private fun checkCompletion() {
        val b = _board.value ?: return
        for (r in 0..8) {
            for (c in 0..8) {
                if (b[r][c] == 0) return
            }
        }
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
     * Toggle a note mark
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

    // ---- Timer ----

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