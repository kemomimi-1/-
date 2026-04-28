# 🧠 EEG Cognitive State V7 (XGBoost Edition) 数据架构说明

## 1. 概述与核心升级背景
**分析引擎**: `eeg_high_accuracy_analyzer.py`
**数据来源**: InfluxDB 3.2.1 (`eeg_db.kaggle_eeg`)
**数据量**: 13,811 采样行
**时间分辨率**: 0.5秒 / 记录

**核心研究挑战**：
1. 原始脑电特性的“同值不同意”困境（Individual Baseline Drift）。不同人的生理基线差异巨大。
2. 传统的 LLM Zero-shot 推理（V3 架构）准确率遇到瓶颈，最高仅达到 ~66.6%，无法满足医疗级或高精度科研需求。

**V7 架构升级 (Solution)**：
彻底转向**传统高精度机器学习与数据科学范式**。引入 XGBoost 极速集成网络与 GPU 加速。通过 112 维的高阶时序特征裂变（包含一阶/二阶差分、双窗口滑动均值与方差），配合严谨的 Subject-Dependent 5-Fold 交叉验证（并严格处理数据隔离避免 Z-Score 泄露），最终实现了平均 **86.88%** 的极高分类准确率。

---

## 2. 112 维特征工程体系 (Feature Engineering)

V7 引擎不仅依赖原始的脑电频段，而是对每个被试的数据进行了深度时序裂变。

### ⚡ 基础脑电波特征 (10 维)
- `delta`, `theta`：慢波。其中 Theta 升高通常反映认知负荷增加。
- `alpha1`, `alpha2`：中速波。反映大脑的放松与松弛。
- `beta1`, `beta2`：快波。与警觉、思维活跃强相关。
- `gamma1`, `gamma2`：极快波。高级认知功能。
- `attention`, `mediation`：NeuroSky 原生计算的专注度与冥想度。

### 🧠 高阶认知合成特征 (6 维)
- `theta_beta`：Theta / Beta 比值。
- `theta_alpha`：Theta / Alpha 比值。
- `alpha_beta`：Alpha / Beta 比值。
- `engagement` (任务参与度)：Beta / (Alpha + Theta)。
- `fatigue` (疲劳指数)：(Alpha + Theta) / Beta。
- `focus`：Beta / (Delta + Theta)。

### ⏳ 时序动态扩展层 (96 维)
针对上述 16 个核心特征，系统会自动计算：
- **一阶差分 (`_d1`)**：当前值与上一秒的差值，捕捉突变。
- **二阶差分 (`_d2`)**：差分的差分，捕捉变化趋势的加速度。
- **短程滑动窗口统计 (`_m3`, `_s3`)**：过去 3 个时间步的均值和标准差，消除高频噪音。
- **中程滑动窗口统计 (`_m5`, `_s5`)**：过去 5 个时间步的均值和标准差，提取中短期稳定趋势。

总计特征维度：10 + 6 + 16*2(差分) + 16*2(3步窗口) + 16*2(5步窗口) = **112 维**。

---

## 3. 分析与验证机制 (Subject-Dependent Analysis)

在面对多被试（Multi-Subject）数据时，系统采用了 **Subject-Dependent 5-Fold CV** 策略：

1. **个体分离**：为每个被试单独筛选出属于他们的数据。
2. **K-Fold 拆分**：将被试自己的数据分为 5 份。
3. **严格 Z-Score**：在每折验证中，仅使用**训练集**计算 Z-Score 的均值和方差，并用其归一化测试集，彻底杜绝数据泄露。
4. **个体建模**：使用 `XGBClassifier(n_estimators=300, max_depth=8)` 针对个人的分布进行独立训练。
5. **性能输出**：各被试准确率独立结算，最后取全局平均，可稳定达到 86% - 91% 的极高学术标准。
