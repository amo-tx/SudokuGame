package com.sudoku.game.engine

/**
 * 数独求解器
 *
 * 算法移植自项目 Python 版 sudoku_solver.py：
 *   - 回溯法 + 候选集剪枝 + MRV 启发式
 *   - 候选数字集合（Candidate Set）加速剪枝
 *   - 选择候选数最少的空格（MRV启发式）减少搜索空间
 *   - 回溯时恢复候选集合
 *
 * 实测：标准数独 < 1ms 求解
 */
class SudokuSolver {

    /**
     * 求解结果
     * @param board 求解后的棋盘（9x9）
     * @param steps 求解步骤列表 (row, col, value)
     * @param success 是否求解成功
     */
    data class SolveResult(
        val board: Array<IntArray>,
        val steps: MutableList<Triple<Int, Int, Int>>,
        val success: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SolveResult) return false
            return success == other.success
        }
        override fun hashCode(): Int = success.hashCode()
    }

    companion object {

        /** 将 (row, col) 映射为一维索引 */
        private fun idx(r: Int, c: Int): Int = r * 9 + c

        /**
         * 求解数独
         * @param board 9x9 int 数组，0 表示空格
         * @return SolveResult 包含解、步骤和是否成功
         */
        fun solve(board: Array<IntArray>): SolveResult {
            val b = Array(9) { board[it].copyOf() }
            val candidates = initCandidates(b)
            val steps = mutableListOf<Triple<Int, Int, Int>>()
            val success = solveHelper(b, candidates, steps)
            return SolveResult(b, steps, success)
        }

        /**
         * 初始化候选数字集合
         * 为每个空格计算可用数字，并传播已有数字的约束
         */
        private fun initCandidates(board: Array<IntArray>): Array<MutableSet<Int>> {
            val cands = Array(81) { mutableSetOf(1, 2, 3, 4, 5, 6, 7, 8, 9) }
            for (r in 0..8) {
                for (c in 0..8) {
                    if (board[r][c] != 0) {
                        cands[idx(r, c)].clear()
                        propagate(cands, r, c, board[r][c])
                    }
                }
            }
            return cands
        }

        /**
         * 传播约束：从同行/同列/同宫移除候选数字
         */
        private fun propagate(cands: Array<MutableSet<Int>>, row: Int, col: Int, num: Int) {
            for (c in 0..8) cands[idx(row, c)].remove(num)
            for (r in 0..8) cands[idx(r, col)].remove(num)
            val br = (row / 3) * 3
            val bc = (col / 3) * 3
            for (r in br until br + 3) {
                for (c in bc until bc + 3) {
                    cands[idx(r, c)].remove(num)
                }
            }
        }

        /**
         * MRV 启发式：选择候选数最少的空格
         * 减少搜索空间，提高求解效率
         */
        private fun selectNextCell(board: Array<IntArray>, cands: Array<MutableSet<Int>>): Pair<Int, Int>? {
            var bestRow = -1
            var bestCol = -1
            var bestCount = 10
            for (r in 0..8) {
                for (c in 0..8) {
                    if (board[r][c] == 0) {
                        val n = cands[idx(r, c)].size
                        if (n < bestCount) {
                            bestCount = n
                            bestRow = r
                            bestCol = c
                            if (n == 0) return Pair(r, c) // 无候选，立即返回触发回溯
                        }
                    }
                }
            }
            return if (bestRow == -1) null else Pair(bestRow, bestCol)
        }

        /**
         * 检查在 (row, col) 放置 num 是否合法
         */
        fun isValid(board: Array<IntArray>, row: Int, col: Int, num: Int): Boolean {
            for (c in 0..8) if (board[row][c] == num) return false
            for (r in 0..8) if (board[r][col] == num) return false
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
         * 更新候选集合，返回变更记录用于回滚
         */
        private fun updateCandidates(
            cands: Array<MutableSet<Int>>,
            row: Int, col: Int, num: Int
        ): List<Triple<Int, Int, Int>> {
            val changed = mutableListOf<Triple<Int, Int, Int>>()
            val cells = HashSet<Pair<Int, Int>>()
            for (c in 0..8) cells.add(Pair(row, c))
            for (r in 0..8) cells.add(Pair(r, col))
            val br = (row / 3) * 3
            val bc = (col / 3) * 3
            for (r in br until br + 3) {
                for (c in bc until bc + 3) {
                    cells.add(Pair(r, c))
                }
            }
            for ((r, c) in cells) {
                if (cands[idx(r, c)].remove(num)) {
                    changed.add(Triple(r, c, num))
                }
            }
            return changed
        }

        /**
         * 回滚候选集合
         */
        private fun rollbackCandidates(cands: Array<MutableSet<Int>>, changed: List<Triple<Int, Int, Int>>) {
            for ((r, c, n) in changed) {
                cands[idx(r, c)].add(n)
            }
        }

        /**
         * 回溯求解递归函数
         */
        private fun solveHelper(
            board: Array<IntArray>,
            cands: Array<MutableSet<Int>>,
            steps: MutableList<Triple<Int, Int, Int>>
        ): Boolean {
            val cell = selectNextCell(board, cands) ?: return true

            val (row, col) = cell
            for (num in cands[idx(row, col)].sorted()) {
                if (!isValid(board, row, col, num)) continue

                board[row][col] = num
                val changed = updateCandidates(cands, row, col, num)
                steps.add(Triple(row, col, num))

                if (solveHelper(board, cands, steps)) return true

                board[row][col] = 0
                rollbackCandidates(cands, changed)
                cands[idx(row, col)].add(num)
            }
            return false
        }

        /**
         * 计算解的数量（上限为 limit），用于验证解的唯一性
         * 当返回值 >= 2 时，说明数独有多解
         */
        fun countSolutions(board: Array<IntArray>, limit: Int = 2): Int {
            val b = Array(9) { board[it].copyOf() }
            val cands = initCandidates(b)
            val count = intArrayOf(0)
            countHelper(b, cands, count, limit)
            return count[0]
        }

        private fun countHelper(
            board: Array<IntArray>,
            cands: Array<MutableSet<Int>>,
            count: IntArray,
            limit: Int
        ): Boolean {
            val cell = selectNextCell(board, cands)
            if (cell == null) {
                count[0]++
                return count[0] >= limit
            }
            val (row, col) = cell
            for (num in cands[idx(row, col)].sorted()) {
                if (!isValid(board, row, col, num)) continue
                board[row][col] = num
                val changed = updateCandidates(cands, row, col, num)
                if (countHelper(board, cands, count, limit)) {
                    board[row][col] = 0
                    rollbackCandidates(cands, changed)
                    return true
                }
                board[row][col] = 0
                rollbackCandidates(cands, changed)
                cands[idx(row, col)].add(num)
            }
            return false
        }

        /**
         * 验证数独是否可解且有唯一解
         */
        fun hasUniqueSolution(board: Array<IntArray>): Boolean {
            return countSolutions(board, 2) == 1
        }

        /**
         * 获取某个空格的所有合法候选数字
         */
        fun getCandidates(board: Array<IntArray>, row: Int, col: Int): Set<Int> {
            if (board[row][col] != 0) return emptySet()
            val result = mutableSetOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
            for (c in 0..8) result.remove(board[row][c])
            for (r in 0..8) result.remove(board[r][col])
            val br = (row / 3) * 3
            val bc = (col / 3) * 3
            for (r in br until br + 3) {
                for (c in bc until bc + 3) {
                    result.remove(board[r][c])
                }
            }
            return result
        }
    }
}
