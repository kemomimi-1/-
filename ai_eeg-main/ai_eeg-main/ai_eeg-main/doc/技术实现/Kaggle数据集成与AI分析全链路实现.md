# Kaggle EEG 数据集成与 AI 分析全链路实现

文档描述了将外部大型开源数据集（以 Kaggle "Confused Student EEG" 为例）无缝接入当前系统，并实现大语言模型（LLM）底层直读、自主分析的前后端架构与技术细节。

## 1. 架构流转总览 (Architecture Overview)
整个数据通道由以下几个环节构成：
1. **输入源**：本地庞大的学术 CSV 脑电波文件集。
2. **加工厂**：Spring Boot 后端（`KaggleEEGImportService`）对乱码数据、异常格式进行清洗，将其转换为 InfluxDB 支持的时序格式。
3. **存储域**：InfluxDB 3.0 接收标准化流式数据（Line Protocol），利用高效引擎进行结构化存储。
4. **智能桥接 (MCP)**：在 `MCPToolRegistry` 定义工具沙盒，让 AI 获取执行查询动作的“智能体执照”。
5. **决策中心**：AI 通过提示工程明确表结构，生成标准 SQL，后端在安全防火墙拦截和检验后放行。

---

## 2. 数据接入与清洗层 (Data Ingestion & Cleaning)
**核心类**：`KaggleEEGImportService.java`

学术数据集通常存在诸多反常的数据结构（例如，部分标量采用科学计数法 `0.00e+00` 而非常规 `0` 或 `1`）。为了正确解析这些数据，系统使用了增强型的强类型转换流：

### 2.1 科学计数法转换防腐
最初因为原数据列（如 `predefinedlabel` 和 `subjectid`）中的科学计数法字符串特性，强加简单的等值匹配（`"1".equals(...)`）会导致：
*   分类标签全部失效退化为 `no`。
*   ID 标签产生高度畸变的命名（如 `S0.000...e+00`）。

**解决方案**：引入基于 `Double.parseDouble()` 的底层安全清洗。所有标识符和二分类特征统一转化为标准浮点数，再进行安全赋值：
```java
// 解决数字与科学计数法的映射碰撞
int rawSubjectId = (int) parseDouble(values, columnIndex, "subjectid", 0);
String subjectId = String.valueOf(rawSubjectId); // 干净地剥离成 "0", "1"

double rawPredefined = parseDouble(values, columnIndex, "predefinedlabel", 0);
// 以 0.5 为边界判断确切的二项标签
String confusedLabel = rawPredefined > 0.5 ? "yes" : "no"; 
```

### 2.2 Line Protocol 高维索引构建
向 InfluxDB 写入时，系统严格分离了 `Tags`（索引）与 `Fields`（场变量），从而大幅提高后续 AI 数据钻取的速度：
*   **Tags**：`subject_id`, `video_id`, `confused`, `self_confused` （常用于 `WHERE` 或 `GROUP BY` 进行分类）
*   **Fields**：`attention`, `theta`, `alpha1...` （必须进行 `AVG()` / `MAX()` 计算的具体波形变量）

---

## 3. 请求代理与时序底座交互层 (MCP Proxy & Database)

这部分解决了 **私有用户数据** 与 **公开科研数据** 混合查询的矛盾。

### 3.1 公有库的数据解封 (Sandbox Exemption)
在原本架构中，为了数据安全，所有前端发起、AI 代理生成的自定义 SQL 都必须经过带有租户隔离强度的 `ensureUserIdFilter` 的拼接处理。
学术数据集（如 `kaggle_eeg`）不带个人用户色彩。我们实施了白名单分流，判断如果检测到 `kaggle_eeg` 关键字，立刻放行，不做无畏的 `user_id` 列拦截检查。

```java
// 截取自 MCPToolRegistry 异常拦截逻辑更新
if (!sql.toLowerCase().contains("kaggle_eeg")) {
    // 为本地流数据隔离租户
    userIdProtectedSql = ensureUserIdFilter(sql, userId); 
} else {
    // 这是一张完全通用的全局量表
    userIdProtectedSql = sql; 
}
```

---

## 4. 智能体约束工程 (Prompt Engineering for Agent)

这是 AI 助手在执行海量 EEG 数据交互时，避免产生“大模型幻觉”的制胜关键。通过在 `MCPTool` 工具中“硬编码 (Hardcoding)”数据字典元信息，系统像“喂设定”一样向大模型注入了数据库法则。

### 4.1 核心字典注入
在 `executeCustomQuery` 工具的描述参数里，必须全面罗列：
*   **表结构（Schema）约束**：直接告诉大模型此表中具有 `theta`, `attention`, `mediation`, `delta` 等固定列名，掐灭其根据语义胡乱推测（如产生 `state = 'confused'` 或 `status` 等）的幻觉温床。

### 4.2 SQL 底层语法禁令防崩 (DataFusion Compliant)
由于 InfluxDB 3 的 DataFusion 内核极其严苛，在 `GROUP BY` 使用上存在严密限制。在给 AI 的底层手册里写入以下绝对禁令：
> **【致命规则】：如果在SQL中使用 GROUP BY，SELECT 子句中必须只包含分组列或聚合函数(如 AVG(theta))，严禁直接 SELECT 原始字段，否则会导致500服务器崩溃错误！**

引入该边界后，AI 的复杂请求输出能自动修正为绝对精准的高级统计学查询（如自动带出 `AVG(attention)`），而不是拉出所有散乱行然后由前端进行庞杂无果的处理。

---
**结论：** 经过这套“摄取清洗 - 协议建表 - Sandbox解封 - MCP提示约束”四步全链路集成，您的 AI EEG 大系统已具备极强的自解析与自主探索科研数据的深度能力。
