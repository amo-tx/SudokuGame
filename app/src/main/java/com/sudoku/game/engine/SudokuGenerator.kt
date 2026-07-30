package com.sudoku.game.engine

import com.sudoku.game.model.Difficulty
import kotlin.random.Random

/**
 * 数独生成器
 *
 * 功能：
 *   1. 生成完整的数独解
 *   2. 根据难度随机挖空单元格
 *   3. 可解性验证（确保有唯一解）
 *   4. 难度验证（根据空格数量和技术需求）
 *
 * 生成策略：
 *   - 先填充对角线三个3x3宫格（互相独立）
 *   - 用随机化回溯求解剩余部分，得到完整解
 *   - 随机选取单元格挖空，每次挖空后验证解的唯一性
 *   - 挖空数量达到难度要求或无法继续挖空时停止
 */
class SudokuGenerator(
    private val random: Random = Random.Default
) {

    /**
     * 生成数独题目
     * @param difficulty 难度等级
     * @return 9x9 int 数组，0 表示空格
     */
    fun generate(difficulty: Difficulty): Array<IntArray> {
        // 步骤1：生成完整解
        val fullBoard = generateFullBoard()

        // 步骤2：随机挖空
        val puzzle = removeCells(fullBoard, difficulty.emptyCells)

        // 步骤3：验证可解性和唯一性（已在挖空过程中保证）
        // 如果验证失败，重新生成
        if (!SudokuSolver.hasUniqueSolution(puzzle)) {
            return generate(difficulty)
        }

        return puzzle
    }

    /**
     * 生成完整的数独解
     * 策略：先填充对角线三个3x3宫格（它们互相独立），再回溯求解
     */
    private fun generateFullBoard(): Array<IntArray> {
        val board = Array(9) { IntArray(9) }

        // 填充对角线三个3x3宫格
        for (i in 0..8 step 3) {
            fillBox(board, i, i)
        }

        // 回溯求解剩余部分
        solveBoardRandomized(board)

        return board
    }

    /**
     * 用随机数字填充一个3x3宫格
     */
    private fun fillBox(board: Array<IntArray>, row: Int, col: Int) {
        val nums = (1..9).shuffled(random)
        var idx = 0
        for (r in row until row + 3) {
            for (c in col until col + 3) {
                board[r][c] = nums[idx++]
            }
        }
    }

    /**
     * 随机化回溯求解（用于生成完整解）
     * 每次尝试候选数字时随机打乱顺序
     */
    private fun solveBoardRandomized(board: Array<IntArray>): Boolean {
        for (r in 0..8) {
            for (c in 0..8) {
                if (board[r][c] == 0) {
                    val nums = (1..9).shuffled(random)
                    for (num in nums) {
                        if (isValidPlacement(board, r, c, num)) {
                            board[r][c] = num
                            if (solveBoardRandomized(board)) return true
                            board[r][c] = 0
                        }
                    }
                    return false
                }
            }
        }
        return true
    }

    /**
     * 检查放置是否合法
     */
    private fun isValidPlacement(board: Array<IntArray>, row: Int, col: Int, num: Int): Boolean {
        for (i in 0..8) {
            if (board[row][i] == num || board[i][col] == num) return false
        }
        val br = (row / 3) * 3
        val bc = (col / 3) * 3
        for (r in br until br + 3) {
            for (c in bc until bc + 3) {
                if (board[r][c] == num) return false
            }
        }
        return true
    }

    /**
     * 随机挖空单元格
     * 每次挖空后验证解的唯一性，如果不唯一则恢复
     *
     * @param board 完整解棋盘
     * @param targetEmpty 目标空格数
     * @return 挖空后的题目棋盘
     */
    private fun removeCells(board: Array<IntArray>, targetEmpty: Int): Array<IntArray> {
        val puzzle = Array(9) { board[it].copyOf() }
        val positions = (0..80).shuffled(random).toMutableList()
        var removed = 0

        for (pos in positions) {
            if (removed >= targetEmpty) break

            val r = pos / 9
            val c = pos % 9
            val backup = puzzle[r][c]
            if (backup == 0) continue

            puzzle[r][c] = 0

            // 验证解的唯一性：确保挖空后仍只有唯一解
            if (!SudokuSolver.hasUniqueSolution(puzzle)) {
                puzzle[r][c] = backup // 恢复
            } else {
                removed++
            }
        }

        return puzzle
    }

    /**
     * 验证难度是否符合要求
     * 根据空格数量和求解步骤数评估
     *
     * @param puzzle 题目棋盘
     * @param difficulty 目标难度
     * @return 是否符合难度要求
     */
    fun verifyDifficulty(puzzle: Array<IntArray>, difficulty: Difficulty): Boolean {
        val emptyCount = puzzle.sumOf { row -> row.count { it == 0 } }

        // 检查空格数量是否在难度范围内
        if (emptyCount < difficulty.emptyCells - 4) return false
        if (emptyCount > difficulty.emptyCells + 4) return false

        // 求解并检查步骤数
        val result = SudokuSolver.solve(puzzle)
        if (!result.success) return false

        // 根据求解步骤数评估难度
        // 步骤越多，通常越难（因为需要更多回溯）
        val steps = result.steps.size
        return when (difficulty) {
            Difficulty.EASY -> steps <= 35
            Difficulty.MEDIUM -> steps in 36..50
            Difficulty.HARD -> steps in 51..65
            Difficulty.EXPERT -> steps > 65
        }
    }

    /**
     * 生成并验证难度的题目
     * 如果难度验证失败，重试最多 maxRetries 次
     */
    fun generateVerified(difficulty: Difficulty, maxRetries: Int = 10): Array<IntArray> {
        repeat(maxRetries) {
            val puzzle = generate(difficulty)
            if (verifyDifficulty(puzzle, difficulty)) {
                return puzzle
            }
        }
        // 如果多次重试失败，返回最后一次生成的题目
        return generate(difficulty)
    }
}
