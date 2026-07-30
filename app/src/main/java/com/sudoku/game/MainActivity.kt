package com.sudoku.game

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.sudoku.game.databinding.ActivityMainBinding
import com.sudoku.game.model.Difficulty
import com.sudoku.game.viewmodel.GameViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: GameViewModel by viewModels()

    private val numberViews = mutableListOf<View>()

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before creating views
        prefs = getSharedPreferences("sudoku_prefs", MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(themeMode)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNumberPad()
        setupDifficultySpinner()
        setupBoardView()
        setupControlButtons()
        setupThemeButton()
        observeViewModel()

        // Show start dialog on launch (don't auto-start)
        showStartDialog()
    }

    /**
     * Show the start dialog where user selects difficulty and begins the game
     */
    private fun showStartDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_start_game, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<android.widget.Button>(R.id.btnEasy).setOnClickListener {
            dialog.dismiss()
            binding.spinnerDifficulty.setSelection(Difficulty.EASY.ordinal)
            viewModel.newGame(Difficulty.EASY)
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnMedium).setOnClickListener {
            dialog.dismiss()
            binding.spinnerDifficulty.setSelection(Difficulty.MEDIUM.ordinal)
            viewModel.newGame(Difficulty.MEDIUM)
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnHard).setOnClickListener {
            dialog.dismiss()
            binding.spinnerDifficulty.setSelection(Difficulty.HARD.ordinal)
            viewModel.newGame(Difficulty.HARD)
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnExpert).setOnClickListener {
            dialog.dismiss()
            binding.spinnerDifficulty.setSelection(Difficulty.EXPERT.ordinal)
            viewModel.newGame(Difficulty.EXPERT)
        }

        dialog.show()
    }

    /**
     * Set up the number pad (1-9 buttons with remaining count)
     */
    private fun setupNumberPad() {
        val inflater = LayoutInflater.from(this)
        for (num in 1..9) {
            val view = inflater.inflate(R.layout.item_number_button, binding.numberPad, false)
            val tvNumber = view.findViewById<TextView>(R.id.tvNumber)
            val tvRemaining = view.findViewById<TextView>(R.id.tvRemaining)
            tvNumber.text = num.toString()
            tvRemaining.text = "9"

            view.setOnClickListener {
                viewModel.inputNumber(num)
            }

            binding.numberPad.addView(view)
            numberViews.add(view)
        }
    }

    /**
     * Set up difficulty spinner
     */
    private fun setupDifficultySpinner() {
        val difficulties = Difficulty.entries.map { it.label }
        val adapter = ArrayAdapter(this, R.layout.item_spinner, difficulties)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDifficulty.adapter = adapter
        binding.spinnerDifficulty.setSelection(Difficulty.MEDIUM.ordinal)

        binding.spinnerDifficulty.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val difficulty = Difficulty.entries[position]
                if (viewModel.difficulty.value != difficulty && viewModel.board.value?.let { board ->
                        board.any { row -> row.any { it != 0 } }
                    } == true) {
                    viewModel.newGame(difficulty)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Set up board view touch callback
     */
    private fun setupBoardView() {
        binding.sudokuBoard.onCellSelected = { row, col ->
            viewModel.selectCell(row, col)
        }
    }

    /**
     * Set up control buttons
     */
    private fun setupControlButtons() {
        binding.btnNewGame.setOnClickListener {
            showStartDialog()
        }

        binding.btnErase.setOnClickListener {
            viewModel.erase()
        }

        binding.btnNote.setOnClickListener {
            viewModel.toggleNoteMode()
        }

        binding.btnHint.setOnClickListener {
            viewModel.hint()
        }

        binding.btnSolve.setOnClickListener {
            viewModel.solveAll()
        }
    }

    /**
     * Set up theme toggle button - cycles: light -> dark -> system -> light
     */
    private fun setupThemeButton() {
        binding.btnTheme.setOnClickListener {
            val currentMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            val newMode = when (currentMode) {
                AppCompatDelegate.MODE_NIGHT_NO -> AppCompatDelegate.MODE_NIGHT_YES
                AppCompatDelegate.MODE_NIGHT_YES -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> AppCompatDelegate.MODE_NIGHT_NO
            }
            prefs.edit().putInt("theme_mode", newMode).apply()
            AppCompatDelegate.setDefaultNightMode(newMode)
            // Activity will be recreated automatically by the system
        }
    }

    /**
     * Observe ViewModel LiveData and update UI
     */
    private fun observeViewModel() {
        viewModel.board.observe(this) { board ->
            binding.sudokuBoard.board = board
        }

        viewModel.given.observe(this) { given ->
            binding.sudokuBoard.given = given
        }

        viewModel.notes.observe(this) { notes ->
            binding.sudokuBoard.notes = notes
        }

        viewModel.selectedCell.observe(this) { cell ->
            binding.sudokuBoard.selectedCell = cell
        }

        viewModel.selectedNumber.observe(this) { num ->
            binding.sudokuBoard.highlightNumber = num
            updateNumberPadSelection(num)
        }

        viewModel.errorCells.observe(this) { errors ->
            binding.sudokuBoard.errorCells = errors
        }

        viewModel.hintCell.observe(this) { cell ->
            binding.sudokuBoard.hintCell = cell
        }

        viewModel.remainingCounts.observe(this) { counts ->
            updateRemainingCounts(counts)
        }

        viewModel.isCompleted.observe(this) { completed ->
            if (completed) {
                showCompletionDialog()
            }
        }

        viewModel.isNoteMode.observe(this) { isNoteMode ->
            binding.btnNote.setTextColor(
                if (isNoteMode) ContextCompat.getColor(this, R.color.purple_500)
                else ContextCompat.getColor(this, R.color.text_given)
            )
        }

        viewModel.mistakeCount.observe(this) { count ->
            binding.tvMistakes.text = count.toString()
        }

        viewModel.elapsedSeconds.observe(this) { seconds ->
            binding.tvTimer.text = formatTime(seconds)
        }

        viewModel.isGenerating.observe(this) { isGenerating ->
            binding.progressBar.visibility = if (isGenerating) View.VISIBLE else View.GONE
            binding.sudokuBoard.visibility = if (isGenerating) View.INVISIBLE else View.VISIBLE
            val controls = listOf(binding.btnErase, binding.btnNote, binding.btnHint, binding.btnSolve)
            controls.forEach { it.isEnabled = !isGenerating }
        }

        viewModel.isSolving.observe(this) { isSolving ->
            binding.btnSolve.isEnabled = !isSolving
            binding.btnHint.isEnabled = !isSolving
        }
    }

    /**
     * Update remaining counts in the number pad
     */
    private fun updateRemainingCounts(counts: IntArray) {
        for (i in 0..8) {
            val view = numberViews[i]
            val tvRemaining = view.findViewById<TextView>(R.id.tvRemaining)
            val tvNumber = view.findViewById<TextView>(R.id.tvNumber)
            val remaining = counts[i]

            tvRemaining.text = remaining.toString()

            if (remaining <= 0) {
                tvNumber.setTextColor(ContextCompat.getColor(this, R.color.text_note))
                view.isEnabled = false
                view.alpha = 0.4f
            } else {
                tvNumber.setTextColor(ContextCompat.getColor(this, R.color.text_given))
                view.isEnabled = true
                view.alpha = 1.0f
            }
        }
    }

    /**
     * Highlight the selected number button
     */
    private fun updateNumberPadSelection(selectedNum: Int) {
        for (i in 0..8) {
            val view = numberViews[i]
            val num = i + 1
            if (num == selectedNum) {
                view.setBackgroundColor(ContextCompat.getColor(this, R.color.number_btn_selected))
            } else {
                view.setBackgroundResource(android.R.color.transparent)
            }
        }
    }

    /**
     * Format time as MM:SS
     */
    private fun formatTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return String.format("%02d:%02d", min, sec)
    }

    /**
     * Show completion dialog
     */
    private fun showCompletionDialog() {
        val time = formatTime(viewModel.elapsedSeconds.value ?: 0)
        val mistakes = viewModel.mistakeCount.value ?: 0
        val difficulty = viewModel.difficulty.value?.label ?: ""

        AlertDialog.Builder(this)
            .setTitle("🎉 恭喜完成！")
            .setMessage("难度：$difficulty\n用时：$time\n错误：$mistakes")
            .setPositiveButton("新游戏") { _, _ ->
                showStartDialog()
            }
            .setNegativeButton("关闭", null)
            .setCancelable(false)
            .show()
    }
}