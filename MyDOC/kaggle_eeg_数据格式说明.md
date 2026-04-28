# Kaggle EEG 数据格式说明

## 数据集背景

- **数据集名称**：Confused Student EEG Brainwave Data
- **来源**：[Kaggle](https://www.kaggle.com/datasets/wanghaohan/confused-eeg)
- **采集设备**：NeuroSky MindSet（单通道消费级脑电设备）
- **实验设计**：10 名学生（S0-S9）分别观看 10 个不同难度的教学视频（V0-V9），通过 EEG 信号判断学生是否处于"困惑"状态
- **数据条数**：25,622 条
- **时间范围**：`2024-01-01 00:00:00` ~ `2024-01-01 05:04:45`
- **存储位置**：InfluxDB `eeg_db` 数据库，表名 `kaggle_eeg`

---

## 字段说明

### 时间与标识字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `time` | Timestamp | 数据采集时间戳（纳秒精度） |
| `subject_id` | String | 被试编号，共 10 人：S0, S1, S2, ..., S9 |
| `video_id` | String | 视频编号，共 10 个视频：V0, V1, V2, ..., V9 |

### NeuroSky 指标字段

| 字段 | 类型 | 取值范围 | 说明 |
|------|------|---------|------|
| `attention` | Float64 | 0 ~ 100 | **注意力指数**。NeuroSky 的 eSense 算法计算得出，值越高表示注意力越集中。0 表示信号质量差，40-60 为基线水平，60-80 为略微集中，80-100 为高度集中 |
| `mediation` | Float64 | 0 ~ 100 | **冥想/放松指数**。NeuroSky 的 eSense 算法计算得出，值越高表示越放松平静。0 表示信号质量差，40-60 为基线水平 |
| `raw` | Float64 | -2048 ~ 2047 | **原始 EEG 信号值**。NeuroSky 芯片直接输出的 12-bit ADC 原始数值，代表前额叶（Fp1）位置的脑电电压 |

### 脑电波段功率字段

这些字段由 NeuroSky 芯片通过 FFT（快速傅里叶变换）实时计算，代表各个频段的功率谱密度值（无量纲，值越大表示该频段活动越强）：

| 字段 | 类型 | 频率范围 | 说明 |
|------|------|---------|------|
| `delta` | Float64 | 0.5 - 4 Hz | **Delta 波功率**。通常与深度睡眠、无意识状态相关 |
| `theta` | Float64 | 4 - 8 Hz | **Theta 波功率**。与放松、冥想、浅睡、白日梦相关 |
| `alpha1` | Float64 | 8 - 10 Hz | **低 Alpha 波功率**。与安静、放松的清醒状态相关 |
| `alpha2` | Float64 | 10 - 13 Hz | **高 Alpha 波功率**。与轻度活跃思维状态相关 |
| `alpha_total` | Float64 | 8 - 13 Hz | **Alpha 波总功率** = alpha1 + alpha2 |
| `beta1` | Float64 | 13 - 20 Hz | **低 Beta 波功率**。与清醒、警觉、主动思考相关 |
| `beta2` | Float64 | 20 - 30 Hz | **高 Beta 波功率**。与紧张、焦虑、高度集中相关 |
| `beta_total` | Float64 | 13 - 30 Hz | **Beta 波总功率** = beta1 + beta2 |
| `gamma1` | Float64 | 30 - 50 Hz | **低 Gamma 波功率**。与高级认知功能相关 |
| `gamma2` | Float64 | 50 - 100 Hz | **高 Gamma 波功率**。与感知绑定、信息整合相关 |
| `gamma_total` | Float64 | 30 - 100 Hz | **Gamma 波总功率** = gamma1 + gamma2 |

### 标签字段

| 字段 | 类型 | 取值 | 说明 |
|------|------|------|------|
| `confused` | String | `yes` / `no` | **人工标注的困惑标签**。由研究人员在实验后标注该时刻学生是否处于困惑状态 |
| `self_confused` | String | `yes` / `no` | **自我报告的困惑标签**。被试自己主观判断是否感到困惑 |

---

## 脑电波段含义速查表

```
频段          频率范围        心理状态
───────────────────────────────────────────────
Delta (δ)     0.5 - 4 Hz    深度睡眠、无意识
Theta (θ)     4 - 8 Hz      放松、冥想、走神、白日梦
Alpha (α)     8 - 13 Hz     清醒但放松（闭眼时最强）
Beta  (β)     13 - 30 Hz    注意力集中、主动思考、紧张
Gamma (γ)     30 - 100 Hz   高级认知、感知整合、学习
───────────────────────────────────────────────
```

> 在困惑检测的场景下，通常关注的核心指标是：
> - **Theta 升高** → 认知负荷增大 → 可能困惑
> - **Alpha 降低** → 注意力不集中或认知努力增加
> - **Beta 升高** → 努力思考 → 但不一定困惑
> - **Attention 降低 + Theta 升高** → 高概率困惑

---

## 示例数据

```
┌─────────────────────────┬─────────┬───────┬───────────┬───────────┬──────┬───────────┬──────────┬──────────┐
│ time                    │ subject │ video │ attention │ mediation │ raw  │ delta     │ confused │ theta    │
├─────────────────────────┼─────────┼───────┼───────────┼───────────┼──────┼───────────┼──────────┼──────────┤
│ 2024-01-01T02:21:33.500 │ S4      │ V6    │ 60        │ 61        │ 28   │ 1483601   │ yes      │ 166294   │
│ 2024-01-01T02:21:34.000 │ S4      │ V6    │ 53        │ 64        │ 227  │ 1564541   │ yes      │ 56860    │
│ 2024-01-01T02:21:34.500 │ S4      │ V6    │ 38        │ 47        │ 3    │ 215712    │ yes      │ 64474    │
└─────────────────────────┴─────────┴───────┴───────────┴───────────┴──────┴───────────┴──────────┴──────────┘
```

> **注意**：`subject_id` 和 `video_id` 存在两种格式（如 `S4` 和 `S4.000000000000000000e+00`），这是之前数据导入时 CSV 中科学计数法解析产生的历史问题，实际上代表相同的被试/视频。

---

## 数据可视化方案

Grafana 更适合做运维监控大屏，对于"写 SQL → 看表格 → 画图表"这种 DataGrip 风格的交互式数据探索，推荐使用 **DBeaver**。

### 推荐工具对比

| 工具 | 风格 | 费用 | 适合场景 |
|------|------|------|---------|
| **DBeaver** ⭐ | 类似 DataGrip | 免费（社区版） | SQL 编辑 + 表格浏览 + 简易图表 |
| DataGrip | JetBrains IDE | 付费 | SQL 编辑 + 表格浏览 + 图表 |
| Grafana | 监控大屏 | 免费 | 实时仪表板、时序监控 |
| Apache Superset | BI 数据分析 | 免费 | 复杂报表、数据探索 |

### DBeaver 安装与连接 InfluxDB 3

#### 1. 下载安装
- 前往 [https://dbeaver.io/download/](https://dbeaver.io/download/)
- 下载 **DBeaver Community Edition**（免费版）
- Windows 用户选择 `.exe` 安装包，双击安装

#### 2. 下载 Flight SQL JDBC 驱动
InfluxDB 3 使用 Flight SQL 协议，需要专门的 JDBC 驱动：
- 前往 [Apache Arrow Flight SQL JDBC Driver](https://central.sonatype.com/artifact/org.apache.arrow/flight-sql-jdbc-driver)
- 下载最新版本的 `.jar` 文件（如 `flight-sql-jdbc-driver-18.x.x.jar`）

#### 3. 在 DBeaver 中配置连接
1. 打开 DBeaver → **数据库** → **新建连接**
2. 选择 **手动配置驱动** 或 **添加新驱动**
3. 驱动信息：
   - **驱动名称**: `InfluxDB 3 Flight SQL`
   - **Class Name**: `org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver`
   - **URL Template**: `jdbc:arrow-flight-sql://localhost:8181?useEncryption=false&database=eeg_db`
   - **JAR 文件**: 指向你下载的 `flight-sql-jdbc-driver-xx.jar`
4. 连接参数：
   - **Host**: `localhost`
   - **Port**: `8181`
   - 因为使用 `--without-auth` 启动，认证信息留空即可

#### 4. 使用方式
连接成功后，你可以像 DataGrip 一样：
- 在左侧浏览数据库结构（表、字段）
- 在编辑器中写 SQL 查询
- 查询结果以表格显示
- 选中结果后点击 **"图表"** 标签页，直接生成柱形图、折线图等可视化
