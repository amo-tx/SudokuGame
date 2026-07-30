# Sudoku Game | 数独游戏

A complete Android Sudoku game built with Kotlin, featuring intelligent puzzle generation, auto-solving with backtracking + MRV heuristic, and a polished UI with number highlighting.

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin)
![Min SDK](https://img.shields.io/badge/min%20SDK-24-00B0FF)
![License](https://img.shields.io/badge/license-MIT-blue)

## Features

| Feature | Description |
|---------|-------------|
| Number Highlighting | Selecting a cell highlights the same number, row, column, and 3x3 box in different blue shades |
| Remaining Count | Bottom number bar shows remaining count for each digit, grays out when depleted |
| Random Puzzle Generation | Generates puzzles via diagonal filling + random backtracking + random emptying |
| Solvability Verification | Verifies unique solution using countSolutions(limit=2) after each cell removal |
| Difficulty Validation | Validates difficulty based on empty cell count and solving steps |
| 4 Difficulty Levels | Easy, Medium, Hard, Expert with progressively more empty cells |
| Auto-Solve | Backtracking + MRV (Minimum Remaining Values) pruning with 50ms step animation |
| Hint System | Provides correct answer for selected cell or finds next empty cell |
| Note Mode | 3x3 candidate number grid in each cell |
| Error Detection | Real-time validation with red highlighting for incorrect inputs |
| Timer | Game timer with completion statistics (time + mistakes) |

## Tech Stack

- **Kotlin** 2.0.21
- **AGP** 8.7.3 / **Gradle** 8.10.2
- **minSdk** 24 / **targetSdk** 35
- **Architecture**: MVVM
- **UI**: Custom View (SudokuBoardView) + ViewBinding
- **Async**: Kotlin Coroutines
- **AndroidX**: AppCompat, Material, Lifecycle, ConstraintLayout

## Algorithm

The core solving algorithm uses **backtracking with MRV (Minimum Remaining Values) heuristic**:

1. Find the empty cell with the fewest valid candidates (MRV)
2. Try each candidate in random order
3. Recursively solve the rest
4. Backtrack if no solution found

Puzzle generation:
1. Fill diagonal 3x3 boxes randomly (independent, no conflicts)
2. Solve the complete board using backtracking
3. Remove cells randomly, verifying unique solution after each removal
4. Stop when target difficulty (empty cell count) is reached

## Build

`
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
`

APK output: pp/build/outputs/apk/release/app-release.apk

## Download

Pre-built APKs are available in [Releases](../../releases). Download and install on Android 7.0+.

## License

MIT License - see [LICENSE](LICENSE)