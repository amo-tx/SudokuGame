package com.sudoku.game.model

/**
 * 数独难度枚举
 * emptyCells: 该难度下挖空的单元格数量
 * label: 显示名称
 */
enum class Difficulty(val emptyCells: Int, val label: String) {
    EASY(36, "简单"),
    MEDIUM(44, "中等"),
    HARD(50, "困难"),
    EXPERT(55, "专家");

    companion object {
        fun fromLabel(label: String): Difficulty {
            return entries.find { it.label == label } ?: MEDIUM
        }
    }
}
