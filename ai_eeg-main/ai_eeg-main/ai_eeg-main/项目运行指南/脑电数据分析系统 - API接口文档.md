# 脑电数据分析系统 - API接口文档

> **Base URL：** `http://localhost:8080`  
> **认证方式：** 基于HttpSession，登录成功后Session中存储userId  
> **Content-Type：** `application/json`

**通用响应格式：**

```json
// 成功
{ "success": true, "message": "操作成功", "timestamp": 1712345678, ...其他字段 }

// 失败
{ "success": false, "error": "错误描述", "timestamp": 1712345678 }
```

---

## 1. 用户认证接口（/api/auth）

### 1.1 POST /api/auth/register — 用户注册

创建新用户账号，用户名和密码有格式要求。

**请求体：**

```json
{
  "username": "testuser01",
  "password": "Password123"
}
```

| 参数 | 类型 | 必填 | 说明 |
|:---|:---|:---|:---|
| username | String | 是 | 6-20位，仅允许字母和数字 |
| password | String | 是 | 8-30位，必须包含至少一个字母和一个数字 |

**成功响应（200）：**

```json
{
  "message": "注册成功",
  "userId": 1,
  "username": "testuser01"
}
```

**失败响应（400）：**

```json
{ "error": "用户名已存在" }
```

### 1.2 POST /api/auth/login — 用户登录

验证用户名和密码，成功后在HttpSession中设置userId和username。

**请求体：**

```json
{
  "username": "testuser01",
  "password": "Password123"
}
```

**成功响应（200）：**

```json
{
  "message": "登录成功",
  "userId": 1,
  "username": "testuser01"
}
```

**失败响应（401）：**

```json
{ "error": "用户名或密码错误" }
```

### 1.3 POST /api/auth/logout — 退出登录

销毁当前Session。需要已登录。

**请求体：** 无

**成功响应：**

```json
{ "message": "已退出登录" }
```

### 1.4 GET /api/auth/status — 获取认证状态

查看当前是否已登录。不需要认证。

**成功响应（已登录）：**

```json
{
  "authenticated": true,
  "userId": 1,
  "username": "testuser01"
}
```

**成功响应（未登录）：**

```json
{
  "authenticated": false
}
```

---

## 2. EEG连接管理接口（/api/connection）

### 2.1 POST /api/connection/request — 请求数据连接

为当前用户分配3个UDP端口（分别用于接收原始数据、滤波数据和频段功率数据），启动数据监听并创建一条新的EEG会话。

**请求体：**

```json
{
  "timezone": "Asia/Shanghai",
  "notes": "测试连接"
}
```

| 参数 | 类型 | 必填 | 说明 |
|:---|:---|:---|:---|
| timezone | String | 否 | 用户时区，默认系统时区 |
| notes | String | 否 | 会话备注 |

**成功响应（200）：**

```json
{
  "success": true,
  "message": "连接请求成功，数据传输会话已创建",
  "sessionId": "EEG-A1B2C3D4-1712345678",
  "ip": "127.0.0.1",
  "ports": {
    "TimeSeriesRaw": 15001,
    "TimeSeriesFilt": 15002,
    "AvgBandPower": 15003
  },
  "timezone": "Asia/Shanghai",
  "instructions": {
    "step1": "在OpenBCI GUI中选择SYNTHETIC(algorithmic) 8chan模式",
    "stream1": "Stream 1: TimeSeriesRaw -> 端口 15001",
    "stream2": "Stream 2: TimeSeriesFilt -> 端口 15002",
    "stream3": "Stream 3: AvgBandPower -> 端口 15003"
  }
}
```

### 2.2 GET /api/connection/status — 获取连接状态

返回当前用户的连接信息，包括端口分配、是否在接收数据、活跃会话等。

**成功响应（200）：**

```json
{
  "success": true,
  "connected": true,
  "ports": {
    "TimeSeriesRaw": 15001,
    "TimeSeriesFilt": 15002,
    "AvgBandPower": 15003
  },
  "activeSession": {
    "sessionId": 1,
    "startTime": "2026-04-07T10:00:00",
    "status": "ACTIVE",
    "totalPackets": 12450
  },
  "receiving": true,
  "packetCounts": {
    "raw": 5230,
    "filt": 4100,
    "band": 3120
  }
}
```

### 2.3 POST /api/connection/disconnect — 断开连接

停止UDP监听，结束当前EEG会话，释放已分配的端口。

**请求体：**

```json
{
  "reason": "用户手动断开"
}
```

| 参数 | 类型 | 必填 | 说明 |
|:---|:---|:---|:---|
| reason | String | 否 | 断开原因，记录到会话备注 |

**成功响应（200）：**

```json
{
  "success": true,
  "message": "连接已断开",
  "sessionSummary": {
    "sessionId": 1,
    "duration": "00:15:32",
    "totalPackets": 12450
  }
}
```

### 2.4 GET /api/connection/ping/{port} — 端口连通性测试

检查指定端口是否在接收数据。

| 路径参数 | 类型 | 说明 |
|:---|:---|:---|
| port | int | 端口号 |

**成功响应（200）：**

```json
{
  "success": true,
  "port": 15001,
  "streamType": "TimeSeriesRaw",
  "receiving": true,
  "packetCount": 5230,
  "lastPacketTime": "2026-04-07T10:15:00"
}
```

### 2.5 GET /api/connection/stream-status — 数据流详细状态

返回三路数据流（raw/filt/band）各自的运行状态。

**成功响应（200）：**

```json
{
  "success": true,
  "streams": {
    "raw": {
      "port": 15001,
      "status": "ACTIVE",
      "totalPackets": 5230,
      "startTime": "2026-04-07T10:00:05",
      "lastPacketTime": "2026-04-07T10:15:00"
    },
    "filt": { ... },
    "band": { ... }
  }
}
```

### 2.6 GET /api/connection/real-time-stats — 实时传输统计

包含数据传输速率、连接状态、最后接收数据等实时信息。

### 2.7 GET /api/connection/port-pool-status — 端口池状态

返回端口池的总端口数、已用端口数、可用端口数等。

### 2.8 POST /api/connection/force-cleanup — 强制清理当前用户连接

调试用接口，强制释放当前用户的端口和会话。

### 2.9 GET /api/connection/debug/cache-consistency — 缓存一致性检查

调试用接口，检查内存中的端口分配状态与数据库中的记录是否一致。

### 2.10 GET /api/connection/debug/user-sessions — 用户会话调试信息

调试用接口，返回用户所有会话（含ACTIVE和历史记录）。

### 2.11 POST /api/connection/debug/force-cleanup-all — 强制清理所有连接

调试用接口，释放所有用户的端口分配和连接。

---

## 3. AI对话接口（/api/ai）

### 3.1 POST /api/ai/query — AI查询（完整返回）

发送一个问题给AI，等待AI完成所有处理（包括可能的MCP工具调用）后一次性返回完整结果。

**请求体：**

```json
{
  "query": "分析一下我最近的脑电数据",
  "sessionId": "conv_user_1_1712345678"
}
```

| 参数 | 类型 | 必填 | 说明 |
|:---|:---|:---|:---|
| query | String | 是 | 用户的问题 |
| sessionId | String | 否 | 对话会话ID。不传则自动创建新会话 |

**成功响应（200）：**

```json
{
  "success": true,
  "response": "根据您最新的脑电数据分析，过去一小时中Alpha频段功率占比较高...",
  "sessionId": "conv_user_1_1712345678",
  "collaborationStats": {
    "totalToolCalls": 3,
    "toolsUsed": ["getActiveSessionContext", "queryLatestBandPowerData", "getUserStatistics"],
    "processingTime": "2.3s"
  }
}
```

### 3.2 POST /api/ai/query/stream — AI查询（SSE流式输出）

与3.1功能相同，但使用Server-Sent Events逐步推送AI的回复内容，适合前端实时展示打字效果。

**请求头：**

```
Content-Type: application/json
Accept: text/event-stream
```

**请求体：** 同3.1

**SSE事件流：**

```
data: {"type":"thinking","content":"正在分析您的查询..."}

data: {"type":"content","content":"根据"}

data: {"type":"content","content":"您最新的"}

data: {"type":"content","content":"脑电数据分析，"}

data: {"type":"tool_call","content":"正在调用 queryLatestBandPowerData..."}

data: {"type":"content","content":"过去一小时中Alpha频段..."}

data: {"type":"done","content":"","sessionId":"conv_user_1_1712345678"}
```

**事件类型说明：**

| type | 说明 |
|:---|:---|
| thinking | AI正在思考/处理 |
| content | AI回复内容（增量推送） |
| tool_call | AI正在调用MCP工具 |
| done | 回复结束，包含sessionId |
| error | 处理过程中出错 |

---

## 4. 数据查询接口（/api/data）

这组接口用于查询InfluxDB中的脑电时序数据，后端会自动在查询条件中添加 `user_id` 过滤，保证数据隔离。

### 4.1 GET /api/data/summary — 数据概要

返回当前用户在各Measurement中的数据记录总数和时间范围。

**成功响应（200）：**

```json
{
  "success": true,
  "summary": {
    "timeseriesraw": { "count": 52300, "earliest": "2026-04-01T...", "latest": "2026-04-07T..." },
    "timeseriesfilt": { "count": 41000, ... },
    "avg_band_power": { "count": 31200, ... }
  }
}
```

### 4.2 GET /api/data/recent — 最近数据

| 查询参数 | 类型 | 必填 | 默认值 | 说明 |
|:---|:---|:---|:---|:---|
| measurement | String | 否 | timeseriesraw | 数据表名：`timeseriesraw` / `timeseriesfilt` / `avg_band_power` |
| limit | int | 否 | 100 | 返回的记录条数 |

### 4.3 GET /api/data/channels — 通道数据

| 查询参数 | 类型 | 必填 | 默认值 | 说明 |
|:---|:---|:---|:---|:---|
| channel | int | 否 | 1 | 通道号（1-8） |
| measurement | String | 否 | timeseriesraw | 数据表 |
| hours | int | 否 | 1 | 查询最近多少小时 |

### 4.4 GET /api/data/bandpower — 频段功率数据

| 查询参数 | 类型 | 必填 | 默认值 | 说明 |
|:---|:---|:---|:---|:---|
| band | String | 否 | alpha | 频段名：`alpha` / `beta` / `theta` / `delta` / `gamma` |
| hours | int | 否 | 1 | 查询最近多少小时 |

### 4.5 POST /api/data/query — 自定义SQL查询

允许用户（或AI）发送自定义SQL查询InfluxDB中的数据。后端会强制在WHERE条件中注入 `user_id` 过滤，防止越权访问。

**请求体：**

```json
{
  "query": "SELECT time, value FROM avg_band_power WHERE band = 'alpha' ORDER BY time DESC LIMIT 50",
  "format": "json"
}
```

| 参数 | 类型 | 必填 | 说明 |
|:---|:---|:---|:---|
| query | String | 是 | SQL查询语句 |
| format | String | 否 | 返回格式，默认 `json`，也支持 `csv` |

### 4.6 GET /api/data/stats — 数据统计

返回三个Measurement的统计信息（记录数、时间范围等）。

### 4.7 GET /api/data/connection-test — InfluxDB连通性检测

测试InfluxDB是否可以正常连接和查询。

### 4.8 GET /api/data/tables — InfluxDB数据表列表

相当于执行 `SHOW TABLES`，返回InfluxDB中的所有Measurement名称。

---

## 5. 实时分析弹幕接口（/api/realtime-analysis）

### 5.1 GET /api/realtime-analysis/status — 获取分析状态

返回当前用户的实时分析是否正在运行。

**成功响应（200）：**

```json
{
  "success": true,
  "analysisActive": true,
  "userId": 1
}
```

### 5.2 POST /api/realtime-analysis/start — 启动实时分析

启动后，后台将每隔7秒执行一次频谱分析，生成弹幕并通过WebSocket推送。

**请求体：** 无

**成功响应（200）：**

```json
{
  "success": true,
  "message": "实时分析已启动",
  "userId": 1,
  "analysisActive": true,
  "strategy": "智能数据获取：实时数据 -> 历史数据 -> 持续采样"
}
```

### 5.3 POST /api/realtime-analysis/stop — 停止实时分析

停止周期性分析任务，清理用户的活跃状态。

**请求体：** 无

**成功响应（200）：**

```json
{
  "success": true,
  "message": "实时分析已停止",
  "userId": 1,
  "analysisActive": false
}
```

### 5.4 GET /api/realtime-analysis/barrages — 获取历史弹幕

分页查询用户的弹幕记录，按创建时间倒序排列。

| 查询参数 | 类型 | 必填 | 默认值 | 说明 |
|:---|:---|:---|:---|:---|
| limit | int | 否 | 20 | 返回条数 |

**成功响应（200）：**

```json
{
  "success": true,
  "barrages": [
    {
      "id": 1,
      "content": "当前Alpha频段功率较高(0.42)，处于放松状态，注意力水平适中",
      "primaryState": "RELAXED",
      "alertLevel": "NORMAL",
      "alphaValue": 0.42,
      "betaValue": 0.28,
      "thetaValue": 0.15,
      "deltaValue": 0.10,
      "gammaValue": 0.05,
      "dataStartTime": "2026-04-07T10:00:00",
      "dataEndTime": "2026-04-07T10:01:00",
      "sampleCount": 120,
      "confidenceScore": 0.85,
      "dominantFrequency": "Alpha",
      "recommendation": "当前处于理想的放松状态",
      "createdAt": "2026-04-07T10:01:02"
    }
  ],
  "total": 1,
  "userId": 1
}
```

### 5.5 POST /api/realtime-analysis/analyze-now — 立即执行一次分析

触发一次立即分析（不会启动持续的周期性分析），适合手动检查当前状态。

### 5.6 DELETE /api/realtime-analysis/barrages/{barrageId} — 删除弹幕

删除指定的弹幕记录，只能删除自己的弹幕。删除成功后会通过WebSocket通知前端。

| 路径参数 | 类型 | 说明 |
|:---|:---|:---|
| barrageId | Long | 弹幕ID |

**成功响应（200）：**

```json
{
  "success": true,
  "message": "弹幕删除成功",
  "barrageId": 1
}
```

**失败响应（404）：**

```json
{ "success": false, "error": "弹幕不存在或无权限删除" }
```

---

## 6. 对话历史接口（/api/conversations）

### 6.1 POST /api/conversations/new — 创建新对话会话

手动创建一个新的对话会话，返回会话ID。

**成功响应（200）：**

```json
{
  "success": true,
  "sessionId": "conv_user_1_1712345678",
  "message": "新对话会话已创建"
}
```

### 6.2 GET /api/conversations — 对话会话列表（分页）

| 查询参数 | 类型 | 必填 | 默认值 | 说明 |
|:---|:---|:---|:---|:---|
| page | int | 否 | 0 | 页码（从0开始） |
| size | int | 否 | 20 | 每页条数 |

**成功响应（200）：**

```json
{
  "success": true,
  "data": {
    "sessions": [
      {
        "sessionId": "conv_user_1_1712345678",
        "title": "脑电数据分析",
        "lastMessageAt": "2026-04-07T10:30:00",
        "messageCount": 5,
        "isBookmarked": false,
        "usedMcpTools": true
      }
    ],
    "pagination": {
      "currentPage": 0,
      "pageSize": 20,
      "totalPages": 3,
      "totalElements": 42,
      "hasNext": true
    }
  }
}
```

### 6.3 GET /api/conversations/{sessionId}/messages — 获取会话消息列表

按时间正序返回指定会话中的所有消息。

| 路径参数 | 类型 | 说明 |
|:---|:---|:---|
| sessionId | String | 对话会话ID |

**成功响应（200）：**

```json
{
  "success": true,
  "messages": [
    {
      "id": 1,
      "userQuery": "分析一下我最近的数据",
      "aiResponse": "根据您最近一个会话的数据...",
      "conversationTimestamp": "2026-04-07T10:15:00",
      "usedMcpTools": true,
      "toolsUsed": "[\"queryLatestBandPowerData\"]",
      "processingDurationMs": 2340,
      "queryCategory": "EEG_ANALYSIS"
    },
    { ... }
  ],
  "sessionId": "conv_user_1_1712345678",
  "sessionTitle": "脑电数据分析"
}
```

### 6.4 GET /api/conversations/message/{messageId} — 单条消息详情

返回指定消息的完整信息。

### 6.5 PUT /api/conversations/{sessionId}/title — 修改会话标题

**请求体：**

```json
{
  "title": "4月7日脑电分析"
}
```

**成功响应（200）：**

```json
{
  "success": true,
  "message": "标题已更新",
  "sessionId": "conv_user_1_1712345678",
  "newTitle": "4月7日脑电分析"
}
```

### 6.6 POST /api/conversations/{sessionId}/bookmark — 切换收藏状态

调用一次收藏，再调用一次取消收藏（toggle逻辑）。

**成功响应（200）：**

```json
{
  "success": true,
  "message": "已收藏",
  "isBookmarked": true
}
```

### 6.7 GET /api/conversations/bookmarked — 收藏的会话列表

| 查询参数 | 类型 | 必填 | 默认值 |
|:---|:---|:---|:---|
| page | int | 否 | 0 |
| size | int | 否 | 20 |

返回格式同6.2。

### 6.8 DELETE /api/conversations/{sessionId} — 删除对话会话

删除指定会话及其下所有消息。

### 6.9 DELETE /api/conversations/batch — 批量删除

**请求体：**

```json
{
  "sessionIds": ["conv_001", "conv_002", "conv_003"]
}
```

**成功响应（200）：**

```json
{
  "success": true,
  "message": "批量删除成功",
  "deletedCount": 3
}
```

### 6.10 GET /api/conversations/statistics — 对话统计

**成功响应（200）：**

```json
{
  "success": true,
  "statistics": {
    "totalSessions": 42,
    "totalMessages": 156,
    "bookmarkedSessions": 5,
    "avgProcessingTimeMs": 2100,
    "toolUsageRate": 0.68,
    "categoryCounts": {
      "EEG_ANALYSIS": 45,
      "DATA_QUERY": 32,
      "GENERAL_QUESTION": 20,
      ...
    }
  }
}
```

### 6.11 GET /api/conversations/recent-activity — 最近对话活动

| 查询参数 | 类型 | 必填 | 默认值 |
|:---|:---|:---|:---|
| limit | int | 否 | 10 |

返回最近N条对话消息的摘要。

---

## 7. EEG会话管理接口（/api/sessions）

### 7.1 GET /api/sessions/active — 获取当前活跃会话

返回当前ACTIVE状态的EEG会话信息，如果没有活跃会话则返回null。

**成功响应（200）：**

```json
{
  "success": true,
  "activeSession": {
    "id": 1,
    "sessionStartTime": "2026-04-07T10:00:00",
    "sessionStatus": "ACTIVE",
    "rawStreamStatus": "ACTIVE",
    "filtStreamStatus": "ACTIVE",
    "bandStreamStatus": "ACTIVE",
    "rawStreamTotalPackets": 5230,
    "filtStreamTotalPackets": 4100,
    "bandStreamTotalPackets": 3120,
    "rawPort": 15001,
    "filtPort": 15002,
    "bandPort": 15003,
    "durationSeconds": 932
  }
}
```

### 7.2 POST /api/sessions/end — 手动结束活跃会话

将当前活跃会话的状态设为COMPLETED，记录结束时间。

### 7.3 GET /api/sessions/history — 会话历史

| 查询参数 | 类型 | 必填 | 默认值 | 说明 |
|:---|:---|:---|:---|:---|
| limit | int | 否 | 10 | 返回最近的N个会话 |

### 7.4 GET /api/sessions/latest-completed — 最近完成的会话

返回最近一个状态为COMPLETED的会话详情。

### 7.5 GET /api/sessions/by-time-range — 按时间范围查询

| 查询参数 | 类型 | 必填 | 说明 |
|:---|:---|:---|:---|
| startTime | String | 是 | 开始时间，格式 `yyyy-MM-dd'T'HH:mm:ss` |
| endTime | String | 是 | 结束时间 |
| timezone | String | 否 | 时区，默认系统时区 |

### 7.6 GET /api/sessions/at-time — 查询指定时间点的活跃会话

| 查询参数 | 类型 | 必填 | 说明 |
|:---|:---|:---|:---|
| timePoint | String | 是 | 时间点 |
| timezone | String | 否 | 时区 |

### 7.7 GET /api/sessions/{sessionId} — 会话详情

返回指定会话的完整信息，包括三路数据流的详细状态和统计数据。

| 路径参数 | 类型 | 说明 |
|:---|:---|:---|
| sessionId | Long | 会话ID |

### 7.8 GET /api/sessions/statistics — 用户会话统计

**成功响应（200）：**

```json
{
  "success": true,
  "statistics": {
    "totalSessions": 15,
    "completedSessions": 12,
    "totalDurationMinutes": 480,
    "averageDurationMinutes": 32,
    "totalPackets": {
      "raw": 156000,
      "filt": 124000,
      "band": 93000
    }
  }
}
```

### 7.9 GET /api/sessions/for-ai-analysis — AI分析用会话数据

这个接口专门为AI对话时的MCP工具调用设计，返回适合AI分析的格式化会话数据。

| 查询参数 | 类型 | 必填 | 说明 |
|:---|:---|:---|:---|
| analysisType | String | 否 | `latest`（默认，最新完成的会话）/ `recent`（最近N个）/ `timerange`（时间范围） |
| limit | int | 否 | 当analysisType为recent时有效，默认5 |
| startTime | String | 否 | 当analysisType为timerange时有效 |
| endTime | String | 否 | 同上 |

---

## 8. MCP分析服务接口（/api/mcp/analysis）

这组接口提供多层次的脑电数据分析能力，主要供AI对话中的MCP工具调用，也可以从前端直接调用。

### 8.1 POST /api/mcp/analysis/session-summary — 会话数据摘要

对一个EEG会话的数据进行多层次统计和摘要分析。

**请求体：**

```json
{
  "sessionId": 1,
  "config": {
    "includeRawStats": true,
    "includeFilteredStats": true,
    "includeBandPowerStats": true
  }
}
```

### 8.2 POST /api/mcp/analysis/extract-features — 特征提取

根据研究上下文从指定会话中提取相关特征。

**请求体：**

```json
{
  "sessionId": 1,
  "researchContext": {
    "researchType": "ATTENTION_MONITORING",
    "targetBands": ["alpha", "beta"],
    "timeResolution": "1min"
  }
}
```

### 8.3 POST /api/mcp/analysis/compare-sessions — 多会话对比

对比多个会话之间的数据差异。

**请求体：**

```json
{
  "sessionIds": [1, 2, 3],
  "comparisonType": "general",
  "metrics": ["duration", "bandpower", "quality"]
}
```

### 8.4 POST /api/mcp/analysis/research-analysis — 研究导向分析

针对具体的研究问题进行定向分析。

**请求体：**

```json
{
  "researchQuestion": "用户的注意力集中程度随时间如何变化？",
  "sessionIds": [1, 2],
  "studyParameters": {
    "focusBands": ["alpha", "beta"],
    "timeWindows": "5min"
  }
}
```

### 8.5 GET /api/mcp/analysis/data-quality/{sessionId} — 数据质量评估

评估指定会话的数据质量。

| 路径参数 | 类型 | 说明 |
|:---|:---|:---|
| sessionId | Long | 会话ID |

| 查询参数 | 类型 | 必填 | 默认值 | 说明 |
|:---|:---|:---|:---|:---|
| assessmentLevel | String | 否 | basic | 评估级别：`basic` / `comprehensive` |

### 8.6 GET /api/mcp/analysis/capabilities — 分析能力清单

返回系统支持的所有分析类型、研究类型、频段信息和电极位置等元数据信息。

**成功响应（200）：**

```json
{
  "success": true,
  "capabilities": {
    "analysisTypes": ["session_summary", "feature_extraction", "comparison", "research"],
    "supportedBands": ["alpha", "beta", "theta", "delta", "gamma"],
    "supportedResearchTypes": ["ATTENTION_MONITORING", "SLEEP_STUDY", "MEDITATION", "COGNITIVE_LOAD"],
    "channels": 8,
    "electrodePositions": ["Fp1", "Fp2", "C3", "C4", "P7", "P8", "O1", "O2"]
  }
}
```

---

## 9. 端口池管理接口（/api/admin/ports）

管理员级别的端口池管理接口。

### 9.1 GET /api/admin/ports/statistics — 端口池统计

**成功响应（200）：**

```json
{
  "success": true,
  "statistics": {
    "totalPorts": 16845,
    "usedPorts": 6,
    "availablePorts": 16839,
    "usageRate": 0.0004,
    "activeUsers": 2
  }
}
```

### 9.2 GET /api/admin/ports/allocations/active — 活跃端口分配

返回当前所有正在使用中的端口分配信息。

### 9.3 POST /api/admin/ports/allocations/force-release — 强制释放端口

**请求体：**

```json
{
  "userId": 1,
  "reason": "管理员手动释放"
}
```

### 9.4 GET /api/admin/ports/health — 端口池健康检查

返回端口池的健康状态。

**成功响应（200）：**

```json
{
  "success": true,
  "health": {
    "status": "HEALTHY",
    "usageRate": 0.0004,
    "availablePorts": 16839,
    "warnings": []
  }
}
```

health.status 可能的值：`HEALTHY` / `WARNING`（使用率>70%）/ `CRITICAL`（使用率>90%）

---

## 10. WebSocket接口

### 10.1 连接端点

```
ws://localhost:8080/ws/eeg
```

允许所有来源（AllowedOrigins: *）。

### 10.2 推送消息格式

所有推送消息为JSON格式：

**新弹幕推送：**

```json
{
  "type": "NEW_BARRAGE",
  "barrage": {
    "id": 1,
    "content": "当前处于放松状态...",
    "primaryState": "RELAXED",
    "alertLevel": "NORMAL",
    ...
  },
  "timestamp": 1712345678000
}
```

**弹幕删除通知：**

```json
{
  "type": "BARRAGE_DELETED",
  "barrageId": 1,
  "content": "已删除的弹幕内容",
  "timestamp": 1712345678000
}
```

### 10.3 前端连接示例

```javascript
const ws = new WebSocket('ws://localhost:8080/ws/eeg');

ws.onopen = function() {
    console.log('WebSocket已连接');
};

ws.onmessage = function(event) {
    const data = JSON.parse(event.data);
    
    switch(data.type) {
        case 'NEW_BARRAGE':
            // 渲染弹幕动画
            displayBarrage(data.barrage);
            break;
        case 'BARRAGE_DELETED':
            // 移除弹幕元素
            removeBarrage(data.barrageId);
            break;
    }
};

ws.onclose = function() {
    console.log('WebSocket已断开');
};
```

---

## 接口汇总

| 模块 | 接口数量 | 基础路径 |
|:---|:---|:---|
| 用户认证 | 4 | /api/auth |
| EEG连接管理 | 11 | /api/connection |
| AI对话 | 2 | /api/ai |
| 数据查询 | 8 | /api/data |
| 实时弹幕分析 | 6 | /api/realtime-analysis |
| 对话历史 | 11 | /api/conversations |
| EEG会话管理 | 9 | /api/sessions |
| MCP分析服务 | 6 | /api/mcp/analysis |
| 端口池管理 | 4 | /api/admin/ports |
| WebSocket | 1 | /ws/eeg |
| **合计** | **62** | — |
