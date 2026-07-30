package com.sudoku.game

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sudoku.game.databinding.ActivityMainBinding
import com.sudoku.game.model.Difficulty
import com.sudoku.game.viewmodel.GameViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: GameViewModel by viewModels()

    // 数字按钮视图缓存
    private val numberViews = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNumberPad()
        setupDifficultySpinner()
        setupBoardView()
        setupControlButtons()
        observeViewModel()

        // 启动初始游戏
        viewModel.newGame(Difficulty.MEDIUM)
    }

    /**
     * 设置数字候选栏（1-9 按钮）
     * 每个按钮显示数字和剩余数量
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
     * 设置难度选择器
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
                if (viewModel.difficulty.value != difficulty) {
                    viewModel.newGame(difficulty)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * 设置棋盘视图的触摸回调
     */
    private fun setupBoardView() {
        binding.sudokuBoard.onCellSelected = { row, col ->
            viewModel.selectCell(row, col)
        }
    }

    /**
     * 设置控制按钮
     */
    private fun setupControlButtons() {
        binding.btnNewGame.setOnClickListener {
            val difficulty = viewModel.difficulty.value ?: Difficulty.MEDIUM
            viewModel.newGame(difficulty)
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
     * 观察 ViewModel 的 LiveData，更新 UI
     */
    private fun observeViewModel() {
        // 棋盘数据
        viewModel.board.observe(this) { board ->
            binding.sudokuBoard.board = board
        }

        // 给定数字标记
        viewModel.given.observe(this) { given ->
            binding.sudokuBoard.given = given
        }

        // 笔记
        viewModel.notes.observe(this) { notes ->
            binding.sudokuBoard.notes = notes
        }

        // 选中单元格
        viewModel.selectedCell.observe(this) { cell ->
            binding.sudokuBoard.selectedCell = cell
        }

        // 高亮数字（选中单元格的值）
        viewModel.selectedNumber.observe(this) { num ->
            binding.sudokuBoard.highlightNumber = num
            updateNumberPadSelection(num)
        }

        // 错误单元格
        viewModel.errorCells.observe(this) { errors ->
            binding.sudokuBoard.errorCells = errors
        }

        // 提示单元格
        viewModel.hintCell.observe(this) { cell ->
            binding.sudokuBoard.hintCell = cell
        }

        // 剩余数字数量
        viewModel.remainingCounts.observe(this) { counts ->
            updateRemainingCounts(counts)
        }

        // 游戏完成
        viewModel.isCompleted.observe(this) { completed ->
            if (completed) {
                showCompletionDialog()
            }
        }

        // 笔记模式
        viewModel.isNoteMode.observe(this) { isNoteMode ->
            binding.btnNote.setTextColor(
                if (isNoteMode) Color.parseColor("#6750A4")
                else Color.parseColor("#1A1A1A")
            )
        }

        // 错误计数
        viewModel.mistakeCount.observe(this) { count ->
            binding.tvMistakes.text = count.toString()
        }

        // 计时器
        viewModel.elapsedSeconds.observe(this) { seconds ->
            binding.tvTimer.text = formatTime(seconds)
        }

        // 生成中状态
        viewModel.isGenerating.observe(this) { isGenerating ->
            binding.progressBar.visibility = if (isGenerating) View.VISIBLE else View.GONE
            binding.sudokuBoard.visibility = if (isGenerating) View.INVISIBLE else View.VISIBLE
            val controls = listOf(binding.btnErase, binding.btnNote, binding.btnHint, binding.btnSolve)
            controls.forEach { it.isEnabled = !isGenerating }
        }

        // 解题中状态
        viewModel.isSolving.observe(this) { isSolving ->
            binding.btnSolve.isEnabled = !isSolving
            binding.btnHint.isEnabled = !isSolving
        }
    }

    /**
     * 更新数字候选栏的剩余数量显示
     */
    private fun updateRemainingCounts(counts: IntArray) {
        for (i in 0..8) {
            val view = numberViews[i]
            val tvRemaining = view.findViewById<TextView>(R.id.tvRemaining)
            val tvNumber = view.findViewById<TextView>(R.id.tvNumber)
            val remaining = counts[i]

            tvRemaining.text = remaining.toString()

            // 剩余为0时禁用按钮并变灰
            if (remaining <= 0) {
                tvNumber.setTextColor(Color.parseColor("#BDBDBD"))
                view.isEnabled = false
                view.alpha = 0.4f
            } else {
                tvNumber.setTextColor(Color.parseColor("#1A1A1A"))
                view.isEnabled = true
                view.alpha = 1.0f
            }
        }
    }

    /**
     * 高亮当前选中的数字按钮
     */
    private fun updateNumberPadSelection(selectedNum: Int) {
        for (i in 0..8) {
            val view = numberViews[i]
            val num = i + 1
            if (num == selectedNum) {
                view.setBackgroundColor(Color.parseColor("#E3F2FD"))
            } else {
                view.setBackgroundResource(android.R.color.transparent)
            }
        }
    }

    /**
     * 格式化时间
     */
    private fun formatTime(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return String.format("%02d:%02d", min, sec)
    }

    /**
     * 显示完成对话框
     */
    private fun showCompletionDialog() {
        val time = formatTime(viewModel.elapsedSeconds.value ?: 0)
        val mistakes = viewModel.mistakeCount.value ?: 0
        val difficulty = viewModel.difficulty.value?.label ?: ""

        AlertDialog.Builder(this)
            .setTitle("🎉 恭喜完成！")
            .setMessage("难度：$difficulty\n用时：$time\n错误：$mistake")
            .setPositiveButton("新游戏") { _, _ ->
                val diff = viewModel.difficulty.value ?: Difficulty.MEDIUM
                viewModel.newGame(diff)
            }
            .setNegativeButton("关闭", null)
            .setCancelable(false)
            .show()
    }
}
