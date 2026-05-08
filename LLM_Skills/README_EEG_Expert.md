## ⚙️ 环境依赖 (Dependencies)
在运行系统之前，请确保您的 Python 环境中安装了以下核心依赖：
```bash
pip install pandas numpy xgboost scikit-learn influxdb-client-3 scipy matplotlib neurora
```
*注：`analyze` 功能在检测到可用 CUDA 时会自动开启 GPU 加速，否则平滑降级到 CPU 多核处理。*

---

## 🚀 运行方式 (Usage)

本系统支持 **交互式菜单界面** 与 **CLI 命令行** 两种操作流，方便各种复杂的使用场景。

### 模式 1：交互式主菜单 (推荐新手)
直接运行脚本，系统会唤出一个友好的终端菜单：
```bash
python eeg_expert.skills
```
菜单提供以下选项供选择：
1. `[Analyze]` 启动高精诊断引擎
2. `[Import]` 导入外部 CSV 数据
3. `[Inspect]` 巡检数据库状态
4. `[Route A]` 运行神经机制分析手册
5. `q` 退出系统

### 模式 2：CLI 命令行直调 (推荐极客/自动化脚本)
如果你知道自己需要做什么，可以直接在命令行传递参数来跳过菜单直接执行核心任务：

#### 1. 高精度诊断引擎 (Analyze)
自动扫描数据库，提示用户选择希望分析的数据表，然后提取 240 维高阶时序特征（包含相对功率、大窗口滑动方差与多阶差分），并执行严格的 Subject-Dependent 5-Fold 交叉验证。
```bash
# 扫描 eeg_db 中的表并弹出交互提示，让用户选择要分析的表
python eeg_expert.skills analyze

# 仅分析特定的表 (例如 external_data)
python eeg_expert.skills analyze --table external_data
```

#### 2. CSV 数据导入器 (Import)
将采集到的离线 `.csv` 格式 EEG 数据智能清洗后写入 InfluxDB，自动映射电极列并填补缺失频段。
```bash
python eeg_expert.skills import C:\path\to\your_data.csv --table kaggle_eeg
```

#### 3. 数据库巡检 (Inspect)
极速掌握当前 InfluxDB 数据库的状态，包括总行数、有效被试、标签均衡度以及各频段极值与标准差。
```bash
python eeg_expert.skills inspect
```

#### 4. 神经机制分析 (Route A)
执行 NeuroRA 脑电手册方法，基于张量转换和聚类置换检验 (Cluster-based Permutation Test) 绘制逐时间点解码准确率。
```bash
python eeg_expert.skills route_a
```
*执行完毕后将在桌面自动生成名为 `neurora_hybrid_result.png` 的可视化热图。*

---

## 🗄️ 底层数据库要求 & 跨平台配置
本系统高度依赖 InfluxDB 作为时序存储核心。

**方式 1：修改代码头部配置（推荐）**
使用任何文本编辑器打开 `eeg_expert.skills`，修改脚本最前方的全局变量：
```python
INFLUX_HOST = "http://localhost:8181"
INFLUX_DB = "eeg_db"
INFLUX_TOKEN = "apiv3_token"
INFLUX_ORG = ""
OUTPUT_DIR = "."  # 输出文件的保存路径
```

**方式 2：使用环境变量（适合自动化）**
无需修改代码，直接在运行终端前配置环境变量：
```bash
# Windows (PowerShell)
$env:INFLUX_HOST="http://192.168.1.100:8181"
$env:INFLUX_TOKEN="your_token"
python eeg_expert.skills
```
