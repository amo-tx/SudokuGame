package com.sudoku.game.model

/**
 * 数独单元格状态
 */
data class Cell(
    val row: Int,
    val col: Int,
    val value: Int,        // 0表示空格
    val isGiven: Boolean,  // 是否为题目给定的数字
    val isError: Boolean = false,
    val isHint: Boolean = false
) {
    val isEmpty: Boolean get() = value == 0
}
