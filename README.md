# 数独游戏 (Sudoku Game)

基于Android平台的数独游戏应用，集成手写数字识别功能，支持拍照/相册上传数独题目自动识别。

## 功能特性

### 游戏核心
- **数独生成**：对角线填充 + 随机回溯求解 + 随机挖空，保证唯一解
- **数独求解**：回溯算法 + MRV启发式 + 候选集剪枝，标准数独 < 1ms 求解
- **难度系统**：简单(36空) / 中等(44空) / 困难(50空) / 专家(55空)
- **错误检测**：基于数独规则(行/列/宫冲突)实时检测，3次错误游戏结束
- **计时器**：支持暂停/恢复

### 辅助功能
- **提示**：实时求解当前棋盘，自动填充一个正确答案
- **自动解题**：逐格动画展示求解过程
- **笔记模式**：在空格中记录候选数字
- **剩余计数**：数字键盘显示每个数字的剩余可用数量
- **高亮显示**：选中格、同行/列/宫、相同数字多层高亮

### 手写数字识别
- 基于CNN模型(ONNX Runtime)推理
- 支持拍照和相册上传
- 图像预处理：灰度化 → 缩放 → 投影网格检测 → 81格分割 → 墨迹检测 → 32x32归一化
- 批量推理识别81个单元格

### 界面与主题
- Material Design 设计风格
- 浅色/深色/跟随系统 三种主题
- 暂停菜单（继续游戏/新游戏/返回主菜单）

## 用户操作流程

```
主菜单（新游戏 / 继续游戏 / 扫描数独 / 主题设置）
  ├─ 新游戏 → 选择难度 → 根据难度出题
  ├─ 继续游戏 → 从存档恢复
  └─ 扫描数独 → 拍照/相册 → CNN识别 → 加载棋盘
      ↓
用户解题 / 程序提示 / 程序自动解题
      ↓
累计错误达3次 或 解完问题 → 结束菜单（再来一局/返回主菜单/关闭）
```

> **设计原则**：程序不预存出题答案，提示和自动解题均通过实时求解器计算。

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 1.9.20 |
| 构建 | Gradle 8.2 / AGP 8.2.0 |
| 最低SDK | Android 7.0 (API 24) |
| 目标SDK | Android 14 (API 34) |
| 架构 | MVVM (ViewModel + LiveData) |
| UI | Material Design Components |
| ML推理 | ONNX Runtime |
| 主题 | DayNight |

## 项目结构

```
SudokuApp/
├── app/src/main/java/com/sudoku/game/
│   ├── MainActivity.kt              # 主界面，用户流程控制
│   ├── engine/
│   │   ├── SudokuGenerator.kt       # 数独生成器（对角线填充+回溯+挖空）
│   │   ├── SudokuSolver.kt          # 数独求解器（回溯+MRV+候选集剪枝）
│   │   └── SudokuRecognizer.kt      # 手写数字识别（ONNX CNN推理）
│   ├── model/
│   │   ├── Cell.kt                  # 单元格数据类
│   │   └── Difficulty.kt            # 难度枚举
│   ├── view/
│   │   └── SudokuBoardView.kt       # 自定义数独棋盘View
│   └── viewmodel/
│       └── GameViewModel.kt         # 游戏核心状态管理
├── app/src/main/res/
│   ├── drawable/                    # 矢量图标
│   ├── layout/                      # 布局文件
│   ├── values/                      # 颜色、字符串、主题
│   └── values-night/                # 深色主题资源
├── app/src/main/assets/
│   ├── sudoku_digit.onnx            # CNN数字识别模型
│   └── sudoku_digit_handwritten.onnx # 手写数字识别模型
└── .github/workflows/build.yml      # GitHub Actions CI/CD
```

## 核心算法

### 数独求解
移植自Python项目，采用回溯法 + 候选集剪枝 + MRV启发式：
- **候选集**：为每个空格维护可用数字集合，填入数字时传播约束
- **MRV启发式**：每次选择候选数最少的空格，减少搜索空间
- **回溯**：失败时回滚候选集变更

### 数独生成
1. 对角线3个宫随机填充（互不冲突）
2. 回溯求解完整棋盘
3. 随机挖空指定数量的格子
4. `countSolutions(limit=2)` 验证唯一解

### 手写数字识别
1. 图像灰度化 + 自适应阈值二值化
2. 投影法检测数独网格线
3. 81格分割 + 墨迹检测（判断是否有数字）
4. 32x32双线性插值归一化
5. ONNX CNN批量推理（支持GPU加速）

## 构建

### 环境要求
- JDK 17+
- Android SDK (Build-tools 35.0.0, Platform android-35)
- Gradle Wrapper（项目自带，无需独立安装）

### 本地构建
```bash
# 使用Gradle Wrapper
./gradlew assembleRelease

# 或在Windows上
gradlew.bat assembleRelease
```

### CI/CD
项目配置了GitHub Actions，每次推送到main分支自动构建Debug和Release APK。

## 下载

从 [Releases](https://github.com/amo-tx/SudokuGame/releases) 页面下载最新APK。

## 许可证

MIT License