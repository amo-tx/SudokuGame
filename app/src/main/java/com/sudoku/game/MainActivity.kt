package com.sudoku.game

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.sudoku.game.databinding.ActivityMainBinding
import com.sudoku.game.engine.SudokuRecognizer
import com.sudoku.game.model.Difficulty
import com.sudoku.game.viewmodel.GameViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File

/**
 * MainActivity - 数独游戏主界面
 *
 * 用户操作流程：
 *   主菜单（新游戏/继续游戏/扫描数独）→ 选择难度（仅新游戏）→ 出题
 *   → 用户解题 / 程序提示 / 程序自动解题（程序不知道预先的出题答案）
 *   → 累计错误达到3次或解完问题 → 结束菜单（再来一局/返回主菜单/关闭）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: GameViewModel by viewModels()

    private val numberViews = mutableListOf<View>()
    private lateinit var prefs: SharedPreferences

    // Image recognition
    private var recognizer: SudokuRecognizer? = null
    private var cameraImageUri: Uri? = null

    // Track if a game-over or completion dialog is already showing to prevent duplicates
    private var endDialogShowing = false

    // Activity result launchers
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraImageUri != null) {
            val bitmap = loadBitmapFromUri(cameraImageUri!!)
            if (bitmap != null) {
                recognizeSudoku(bitmap)
            } else {
                Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val bitmap = loadBitmapFromUri(uri)
            if (bitmap != null) {
                recognizeSudoku(bitmap)
            } else {
                Toast.makeText(this, "无法加载图片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, getString(R.string.camera_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

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
        setupScanButton()
        setupPauseButton()
        observeViewModel()

        // Show main menu on launch
        showMainMenu()
    }

    override fun onPause() {
        super.onPause()
        if (!viewModel.isPaused()) {
            viewModel.pauseGame()
        }
        viewModel.saveGame()
    }

    // ================================================================
    // 主菜单
    // ================================================================

    /**
     * 显示主菜单：新游戏 / 继续游戏 / 扫描数独 / 主题设置
     */
    private fun showMainMenu() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_main_menu, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // 新游戏 → 选择难度 → 根据难度出题
        dialogView.findViewById<android.widget.Button>(R.id.btnMenuNewGame).setOnClickListener {
            dialog.dismiss()
            showDifficultyDialog { difficulty ->
                binding.spinnerDifficulty.setSelection(difficulty.ordinal)
                viewModel.newGame(difficulty)
            }
        }

        // 继续游戏 → 从存档恢复
        dialogView.findViewById<android.widget.Button>(R.id.btnMenuContinue).setOnClickListener {
            dialog.dismiss()
            if (viewModel.hasSavedGame()) {
                if (!viewModel.loadSavedGame()) {
                    Toast.makeText(this, R.string.no_saved_game, Toast.LENGTH_SHORT).show()
                    showMainMenu()
                } else {
                    binding.spinnerDifficulty.setSelection(viewModel.difficulty.value?.ordinal ?: 1)
                }
            } else {
                Toast.makeText(this, R.string.no_saved_game, Toast.LENGTH_SHORT).show()
            }
        }

        // 扫描数独 → 拍照/相册上传 → 识别 → 加载棋盘
        dialogView.findViewById<android.widget.Button>(R.id.btnMenuScan).setOnClickListener {
            dialog.dismiss()
            showScanSourceDialog()
        }

        // 主题设置 → 切换主题
        dialogView.findViewById<android.widget.Button>(R.id.btnMenuTheme).setOnClickListener {
            cycleTheme()
        }

        dialog.show()
    }

    /**
     * 显示难度选择对话框
     * @param onSelected 用户选择难度后的回调
     */
    private fun showDifficultyDialog(onSelected: (Difficulty) -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_start_game, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<android.widget.Button>(R.id.btnEasy).setOnClickListener {
            dialog.dismiss()
            onSelected(Difficulty.EASY)
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnMedium).setOnClickListener {
            dialog.dismiss()
            onSelected(Difficulty.MEDIUM)
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnHard).setOnClickListener {
            dialog.dismiss()
            onSelected(Difficulty.HARD)
        }
        dialogView.findViewById<android.widget.Button>(R.id.btnExpert).setOnClickListener {
            dialog.dismiss()
            onSelected(Difficulty.EXPERT)
        }

        dialog.show()
    }

    // ================================================================
    // 结束菜单
    // ================================================================

    /**
     * 显示游戏结束对话框（累计错误达到3次）
     */
    private fun showGameOverDialog() {
        if (endDialogShowing) return
        endDialogShowing = true

        val time = formatTime(viewModel.elapsedSeconds.value ?: 0)
        val difficulty = viewModel.difficulty.value?.label ?: ""

        AlertDialog.Builder(this)
            .setTitle("游戏结束")
            .setMessage("难度：$difficulty\n用时：$time\n错误：${GameViewModel.MAX_MISTAKES}/${GameViewModel.MAX_MISTAKES}")
            .setPositiveButton(R.string.play_again) { _, _ ->
                endDialogShowing = false
                val diff = viewModel.difficulty.value ?: Difficulty.MEDIUM
                viewModel.newGame(diff)
            }
            .setNegativeButton(R.string.back_to_menu) { _, _ ->
                endDialogShowing = false
                showMainMenu()
            }
            .setNeutralButton(R.string.close) { _, _ ->
                endDialogShowing = false
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 显示完成对话框（解完问题）
     */
    private fun showCompletionDialog() {
        if (endDialogShowing) return
        endDialogShowing = true

        val time = formatTime(viewModel.elapsedSeconds.value ?: 0)
        val mistakes = viewModel.mistakeCount.value ?: 0
        val difficulty = viewModel.difficulty.value?.label ?: ""

        AlertDialog.Builder(this)
            .setTitle("恭喜完成！")
            .setMessage("难度：$difficulty\n用时：$time\n错误：$mistakes/${GameViewModel.MAX_MISTAKES}")
            .setPositiveButton(R.string.play_again) { _, _ ->
                endDialogShowing = false
                val diff = viewModel.difficulty.value ?: Difficulty.MEDIUM
                viewModel.newGame(diff)
            }
            .setNegativeButton(R.string.back_to_menu) { _, _ ->
                endDialogShowing = false
                showMainMenu()
            }
            .setNeutralButton(R.string.close) { _, _ ->
                endDialogShowing = false
            }
            .setCancelable(false)
            .show()
    }

    // ================================================================
    // UI 初始化
    // ================================================================

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
     * Set up difficulty spinner - display only, does not auto-restart game
     */
    private fun setupDifficultySpinner() {
        val difficulties = Difficulty.entries.map { it.label }
        val adapter = ArrayAdapter(this, R.layout.item_spinner, difficulties)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDifficulty.adapter = adapter
        binding.spinnerDifficulty.setSelection(Difficulty.MEDIUM.ordinal)
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
            showMainMenu()
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
     * Set up pause button - shows pause menu
     */
    private fun setupPauseButton() {
        binding.btnPause.setOnClickListener {
            if (viewModel.isPaused()) {
                showPauseMenu()
            } else {
                viewModel.pauseGame()
                showPauseMenu()
            }
        }
    }

    /**
     * 显示暂停菜单：继续游戏 / 新游戏 / 主菜单
     */
    private fun showPauseMenu() {
        val time = formatTime(viewModel.elapsedSeconds.value ?: 0)
        val mistakes = viewModel.mistakeCount.value ?: 0
        val difficulty = viewModel.difficulty.value?.label ?: ""

        AlertDialog.Builder(this)
            .setTitle(R.string.paused)
            .setMessage("${getString(R.string.difficulty)}：$difficulty\n${getString(R.string.time_used)}：$time\n${getString(R.string.error_count)}：$mistakes/${GameViewModel.MAX_MISTAKES}")
            .setPositiveButton(R.string.resume) { _, _ ->
                viewModel.resumeGame()
            }
            .setNegativeButton(R.string.new_game) { _, _ ->
                viewModel.resumeGame()
                showDifficultyDialog { difficulty ->
                    binding.spinnerDifficulty.setSelection(difficulty.ordinal)
                    viewModel.newGame(difficulty)
                }
            }
            .setNeutralButton(R.string.back_to_menu) { _, _ ->
                viewModel.resumeGame()
                viewModel.saveGame()
                showMainMenu()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Cycle theme: light -> dark -> system -> light
     */
    private fun cycleTheme() {
        val currentMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val newMode = when (currentMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> AppCompatDelegate.MODE_NIGHT_YES
            AppCompatDelegate.MODE_NIGHT_YES -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        prefs.edit().putInt("theme_mode", newMode).apply()
        AppCompatDelegate.setDefaultNightMode(newMode)
    }

    /**
     * Set up theme toggle button
     */
    private fun setupThemeButton() {
        binding.btnTheme.setOnClickListener {
            cycleTheme()
        }
    }

    // ================================================================
    // 扫描识别
    // ================================================================

    /**
     * Set up scan button
     */
    private fun setupScanButton() {
        binding.btnScan.setOnClickListener {
            showScanSourceDialog()
        }
    }

    /**
     * Show dialog to choose image source: camera or gallery
     */
    private fun showScanSourceDialog() {
        val options = arrayOf(getString(R.string.take_photo), getString(R.string.choose_from_gallery))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.scan_sudoku))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkCameraPermissionAndStart()
                    1 -> startGallery()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Check camera permission and start camera if granted
     */
    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Start camera to take a photo
     */
    private fun startCamera() {
        val imageFile = File(cacheDir, "images/sudoku_camera.jpg")
        imageFile.parentFile?.mkdirs()
        cameraImageUri = FileProvider.getUriForFile(
            this,
            "com.sudoku.game.fileprovider",
            imageFile
        )
        cameraLauncher.launch(cameraImageUri!!)
    }

    /**
     * Start gallery to pick an image
     */
    private fun startGallery() {
        galleryLauncher.launch("image/*")
    }

    /**
     * Load Bitmap from Uri, with size optimization
     */
    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            val maxDim = 1200
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val inputStream2 = contentResolver.openInputStream(uri) ?: return null
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeStream(inputStream2, null, decodeOptions)
            inputStream2.close()
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Recognize Sudoku from bitmap image
     */
    private fun recognizeSudoku(bitmap: Bitmap) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.scan_sudoku))
            .setMessage(getString(R.string.recognizing))
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (recognizer == null) {
                        recognizer = SudokuRecognizer.create(applicationContext)
                    }
                    recognizer!!.recognize(bitmap)
                } catch (e: Exception) {
                    SudokuRecognizer.RecognitionResult(
                        Array(9) { IntArray(9) },
                        false,
                        "识别出错: ${e.message}"
                    )
                }
            }

            progressDialog.dismiss()

            if (result.success) {
                showRecognitionResultDialog(result.board, result.message)
            } else {
                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Show recognition result dialog for user confirmation
     */
    private fun showRecognitionResultDialog(board: Array<IntArray>, message: String) {
        val sb = StringBuilder()
        sb.append("$message\n\n")
        sb.append("┌───────┬───────┬───────┐\n")
        for (r in 0..8) {
            sb.append("│ ")
            for (c in 0..8) {
                val v = board[r][c]
                sb.append(if (v > 0) "$v " else "· ")
                if (c % 3 == 2 && c < 8) sb.append("│ ")
            }
            sb.append("│\n")
            if (r % 3 == 2 && r < 8) {
                sb.append("├───────┼───────┼───────┤\n")
            }
        }
        sb.append("└───────┴───────┴───────┘")

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.recognition_result))
            .setMessage(sb.toString())
            .setPositiveButton(getString(R.string.apply_recognized_board)) { _, _ ->
                viewModel.loadBoard(board)
                Toast.makeText(this, "已加载识别的数独", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ================================================================
    // ViewModel 观察
    // ================================================================

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

        viewModel.isGameOver.observe(this) { gameOver ->
            if (gameOver) {
                showGameOverDialog()
            }
        }

        viewModel.isNoteMode.observe(this) { isNoteMode ->
            binding.btnNote.setTextColor(
                if (isNoteMode) ContextCompat.getColor(this, R.color.purple_500)
                else ContextCompat.getColor(this, R.color.text_given)
            )
        }

        viewModel.mistakeCount.observe(this) { count ->
            binding.tvMistakes.text = "$count/${GameViewModel.MAX_MISTAKES}"
        }

        viewModel.difficulty.observe(this) { difficulty ->
            binding.spinnerDifficulty.setSelection(difficulty.ordinal)
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

        viewModel.isHinting.observe(this) { isHinting ->
            binding.btnHint.isEnabled = !isHinting
        }
    }

    override fun onResume() {
        super.onResume()
        // 如果游戏被系统 onPause 暂停，不自动恢复（等用户从暂停菜单恢复）
    }

    // ================================================================
    // 辅助方法
    // ================================================================

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
}