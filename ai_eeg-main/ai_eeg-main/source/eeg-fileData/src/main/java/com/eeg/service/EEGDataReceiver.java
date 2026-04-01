//  修复断开连接竞争条件的版本
package com.eeg.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class EEGDataReceiver {

    private final InfluxDBService influxDBService;
    private final WebSocketNotificationService webSocketService;
    private final EEGSessionService sessionService;
    private final ObjectMapper objectMapper;

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<Integer, DatagramSocket> activeSockets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, EEGConnectionStatus> userConnections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> packetCounters = new ConcurrentHashMap<>();

    // 【关键修复】添加用户断开连接状态标记，防止竞争条件
    private final ConcurrentHashMap<Long, AtomicBoolean> userDisconnectFlags = new ConcurrentHashMap<>();

    @Data
    public static class EEGConnectionStatus {
        private Long userId;
        private boolean rawConnected = false;
        private boolean filtConnected = false;
        private boolean bandConnected = false;
        private LocalDateTime lastRawData;
        private LocalDateTime lastFiltData;
        private LocalDateTime lastBandData;
        private long rawPacketCount = 0;
        private long filtPacketCount = 0;
        private long bandPacketCount = 0;
        private String sessionId;
        private LocalDateTime connectionStartTime;
    }

    // 添加用户到端口的映射，便于精确控制
    private final ConcurrentHashMap<Long, Set<Integer>> userPortsMap = new ConcurrentHashMap<>();

    /**
     * 启动用户的EEG数据监听，并创建新会话
     */
    public void startListeningForUser(Long userId, int rawPort, int filtPort, int bandPort, String userTimezone) {
        log.info("为用户 {} 启动EEG数据监听 - Raw:{}, Filt:{}, Band:{}", userId, rawPort, filtPort, bandPort);

        // 【修复端口占用】关闭该用户已有的旧 UDP 套接字，防止 BindException
        Set<Integer> existingPorts = userPortsMap.get(userId);
        if (existingPorts != null) {
            log.info("检测到用户 {} 存在旧端口映射，先关闭旧 UDP 套接字", userId);
            userDisconnectFlags.put(userId, new AtomicBoolean(true)); // 先标记断开，防止残留线程处理数据
            for (Integer port : existingPorts) {
                DatagramSocket socket = activeSockets.get(port);
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                    activeSockets.remove(port);
                }
            }
            userPortsMap.remove(userId);
            // 等待 OS 完全释放端口（Windows 下释放稍慢）
            try { Thread.sleep(600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // 【关键修复】清除断开连接标记
        userDisconnectFlags.remove(userId);

        // 记录用户端口映射
        Set<Integer> userPorts = Set.of(rawPort, filtPort, bandPort);
        userPortsMap.put(userId, userPorts);

        // 创建新的数据传输会话
        try {
            sessionService.startNewSession(userId, userTimezone, rawPort, filtPort, bandPort);
        } catch (Exception e) {
            log.error("创建用户 {} 的新会话失败", userId, e);
            return;
        }

        // 初始化连接状态 - 使用正确的UTC时间
        EEGConnectionStatus status = new EEGConnectionStatus();
        status.setUserId(userId);
        status.setConnectionStartTime(getCurrentUtcTime());
        userConnections.put(userId, status);

        // 启动三个UDP监听器
        startUDPListener(userId, rawPort, "TimeSeriesRaw");
        startUDPListener(userId, filtPort, "TimeSeriesFilt");
        startUDPListener(userId, bandPort, "AvgBandPower");

        // 通知前端连接已建立
        webSocketService.notifyUser(userId, "CONNECTION_ESTABLISHED", status);
    }

    /**
     * 启动UDP监听器
     */
    private void startUDPListener(Long userId, int port, String dataType) {
        String listenerKey = userId + "_" + dataType;

        executorService.submit(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(port);
                activeSockets.put(port, socket);
                packetCounters.put(listenerKey, new AtomicLong(0));

                byte[] buffer = new byte[8192];
                log.info("UDP监听器已启动 - 端口:{}, 数据类型:{}, 用户:{}", port, dataType, userId);

                while (!socket.isClosed()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);

                        // 【关键修复】检查用户是否已断开连接
                        AtomicBoolean disconnectFlag = userDisconnectFlags.get(userId);
                        if (disconnectFlag != null && disconnectFlag.get()) {
                            log.debug("用户 {} 已标记为断开连接，忽略数据包 {}", userId, dataType);
                            break;
                        }

                        // 【关键修复】获取当前准确的UTC时间
                        LocalDateTime utcPacketTime = getCurrentUtcTime();

                        log.debug("接收到 {} 数据包 - 用户:{}, UTC时间:{}",
                                dataType, userId, utcPacketTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

                        // 解析并处理数据
                        String jsonData = new String(packet.getData(), 0, packet.getLength());
                        processEEGDataWithSession(userId, dataType, jsonData, utcPacketTime);

                        // 更新连接状态
                        updateConnectionStatus(userId, dataType, utcPacketTime);

                        // 增加数据包计数
                        packetCounters.get(listenerKey).incrementAndGet();

                    } catch (IOException e) {
                        if (!socket.isClosed()) {
                            // 【关键修复】检查是否是正常断开
                            AtomicBoolean disconnectFlag = userDisconnectFlags.get(userId);
                            if (disconnectFlag == null || !disconnectFlag.get()) {
                                log.error("接收UDP数据时出错 - 端口:{}, 用户:{}", port, userId, e);
                            }
                        }
                    }
                }

            } catch (SocketException e) {
                log.error("创建UDP套接字失败 - 端口:{}, 用户:{}", port, userId, e);
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
                activeSockets.remove(port);
                updateConnectionStatus(userId, dataType + "_DISCONNECTED", getCurrentUtcTime());
                log.info("UDP监听器已关闭 - 端口:{}, 数据类型:{}, 用户:{}", port, dataType, userId);
            }
        });
    }

    /**
     * 改进的数据处理方法 - 增加会话状态检查
     */
    private void processEEGDataWithSession(Long userId, String dataType, String jsonData, LocalDateTime utcPacketTime) {
        try {
            // 【关键修复】双重检查用户连接状态
            EEGConnectionStatus connectionStatus = userConnections.get(userId);
            if (connectionStatus == null) {
                log.debug("用户 {} 连接已断开，忽略数据包 {}", userId, dataType);
                return;
            }

            // 【关键修复】检查断开连接标记
            AtomicBoolean disconnectFlag = userDisconnectFlags.get(userId);
            if (disconnectFlag != null && disconnectFlag.get()) {
                log.debug("用户 {} 已标记为断开连接，忽略数据包处理 {}", userId, dataType);
                return;
            }

            JsonNode jsonNode = objectMapper.readTree(jsonData);

            // 确保传递给会话服务的时间是准确的UTC时间
            sessionService.handleDataPacketReceived(userId, dataType, utcPacketTime);

            // 解析数据并写入InfluxDB - 使用相同的UTC时间戳
            if ("TimeSeriesRaw".equals(dataType) || "TimeSeriesFilt".equals(dataType)) {
                processTimeSeriesData(userId, dataType, jsonNode, utcPacketTime);
            } else if ("AvgBandPower".equals(dataType)) {
                processBandPowerData(userId, jsonNode, utcPacketTime);
            }

        } catch (Exception e) {
            log.error("处理EEG数据时出错 - 用户:{}, 类型:{}", userId, dataType, e);
        }
    }

    /**
     * 【关键修复】改进的停止监听方法 - 解决并发竞争问题
     */
    public void stopListeningForUser(Long userId, String reason) {
        log.info("开始停止用户 {} 的EEG数据监听，原因: {}", userId, reason);

        // 【关键修复】立即设置断开连接标记，防止新数据包处理
        userDisconnectFlags.put(userId, new AtomicBoolean(true));

        // 1. 首先从缓存中移除连接状态，防止新数据处理
        EEGConnectionStatus status = userConnections.remove(userId);
        if (status == null) {
            log.warn("用户 {} 没有活跃的连接状态", userId);
        }

        // 2. 获取该用户的端口列表
        Set<Integer> userPorts = userPortsMap.get(userId);

        // 3. 精确关闭该用户的UDP套接字
        if (userPorts != null) {
            for (Integer port : userPorts) {
                DatagramSocket socket = activeSockets.get(port);
                if (socket != null && !socket.isClosed()) {
                    log.info("关闭用户 {} 的端口 {}", userId, port);
                    socket.close();
                    activeSockets.remove(port);
                }
            }
            userPortsMap.remove(userId);
        } else {
            log.warn("未找到用户 {} 的端口映射", userId);
        }

        // 4. 清理数据包计数器
        packetCounters.entrySet().removeIf(entry -> entry.getKey().startsWith(userId + "_"));

        // 【关键修复】等待一小段时间，确保所有UDP操作完成
        try {
            Thread.sleep(500); // 等待500毫秒
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 5. 强制结束用户会话（带重试机制和超时保护）
        int maxRetries = 3;
        boolean sessionEnded = false;

        for (int retry = 0; retry < maxRetries && !sessionEnded; retry++) {
            try {
                log.info("尝试结束用户 {} 的会话 (第{}次尝试)", userId, retry + 1);
                sessionService.forceEndUserSession(userId, reason != null ? reason : "用户手动停止");
                sessionEnded = true;
                log.info("成功结束用户 {} 的会话", userId);
            } catch (Exception e) {
                log.error("结束用户 {} 的会话时出错 (第{}次尝试)", userId, retry + 1, e);
                if (retry < maxRetries - 1) {
                    try {
                        Thread.sleep(1000 * (retry + 1)); // 递增等待时间
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (!sessionEnded) {
            log.error("所有尝试都失败，无法结束用户 {} 的会话，可能需要手动干预", userId);
        }

        // 6. 通知前端连接已关闭
        webSocketService.notifyUser(userId, "CONNECTION_CLOSED", null);

        // 7. 【关键修复】最终清理断开连接标记（延迟清理，防止竞争）
        executorService.submit(() -> {
            try {
                Thread.sleep(5000); // 5秒后清理
                userDisconnectFlags.remove(userId);
                log.debug("已清理用户 {} 的断开连接标记", userId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        log.info("用户 {} 的EEG数据监听停止流程已完成", userId);
    }

    // ... 其他方法保持不变 ...

    /**
     * 处理时间序列数据（原始/滤波）- 使用统一的时间戳
     */
    private void processTimeSeriesData(Long userId, String dataType, JsonNode jsonNode, LocalDateTime utcPacketTime) {
        JsonNode dataArray = jsonNode.get("data");
        if (dataArray != null && dataArray.isArray()) {

            // 【关键修复】使用传入的UTC时间，不再重新转换
            long timestampNanos = convertLocalDateTimeToNanos(utcPacketTime);
            StringBuilder lineProtocol = new StringBuilder();

            log.debug("处理 {} 数据 - UTC时间:{}, 时间戳:{}ns",
                    dataType, utcPacketTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), timestampNanos);

            // 处理8x10的数据矩阵
            for (int channel = 0; channel < dataArray.size(); channel++) {
                JsonNode channelData = dataArray.get(channel);
                if (channelData.isArray()) {

                    for (int sample = 0; sample < channelData.size(); sample++) {
                        double value = channelData.get(sample).asDouble();

                        lineProtocol.append(dataType.toLowerCase())
                                .append(",user_id=").append(userId)
                                .append(",channel=").append(channel + 1)
                                .append(",sample=").append(sample + 1)
                                .append(" value=").append(value)
                                .append(" ").append(timestampNanos + sample * 4000000L) // 250Hz采样率，4ms间隔
                                .append("\n");
                    }
                }
            }

            // 写入InfluxDB
            if (lineProtocol.length() > 0) {
                influxDBService.writeLineProtocol(lineProtocol.toString());
                log.debug("写入 {} 数据到InfluxDB - 用户:{}, 数据点数:{}, UTC时间:{}",
                        dataType, userId, dataArray.size() * 10, utcPacketTime);
            }
        }
    }

    /**
     * 处理频谱功率数据 - 使用统一的时间戳
     */
    private void processBandPowerData(Long userId, JsonNode jsonNode, LocalDateTime utcPacketTime) {
        JsonNode dataArray = jsonNode.get("data");
        if (dataArray != null && dataArray.isArray()) {

            // 【关键修复】使用传入的UTC时间，不再重新转换
            long timestampNanos = convertLocalDateTimeToNanos(utcPacketTime);
            StringBuilder lineProtocol = new StringBuilder();

            log.debug("处理频谱功率数据 - UTC时间:{}, 时间戳:{}ns",
                    utcPacketTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), timestampNanos);

            String[] bands = {"delta", "theta", "alpha", "beta", "gamma"};
            for (int i = 0; i < Math.min(dataArray.size(), bands.length); i++) {
                double value = dataArray.get(i).asDouble();

                lineProtocol.append("avg_band_power")
                        .append(",user_id=").append(userId)
                        .append(",band=").append(bands[i])
                        .append(" value=").append(value)
                        .append(" ").append(timestampNanos)
                        .append("\n");
            }

            // 写入InfluxDB
            if (lineProtocol.length() > 0) {
                influxDBService.writeLineProtocol(lineProtocol.toString());
                log.debug("写入频谱功率数据到InfluxDB - 用户:{}, 频段数:{}, UTC时间:{}",
                        userId, bands.length, utcPacketTime);
            }
        }
    }

    /**
     * 更新连接状态 - 统一使用UTC时间
     */
    private void updateConnectionStatus(Long userId, String dataType, LocalDateTime utcEventTime) {
        EEGConnectionStatus status = userConnections.get(userId);
        if (status != null) {
            boolean statusChanged = false;

            switch (dataType) {
                case "TimeSeriesRaw":
                    if (!status.isRawConnected()) {
                        status.setRawConnected(true);
                        statusChanged = true;
                        log.info("用户 {} 的原始数据流已连接", userId);
                    }
                    status.setLastRawData(utcEventTime);
                    status.setRawPacketCount(status.getRawPacketCount() + 1);
                    break;

                case "TimeSeriesFilt":
                    if (!status.isFiltConnected()) {
                        status.setFiltConnected(true);
                        statusChanged = true;
                        log.info("用户 {} 的滤波数据流已连接", userId);
                    }
                    status.setLastFiltData(utcEventTime);
                    status.setFiltPacketCount(status.getFiltPacketCount() + 1);
                    break;

                case "AvgBandPower":
                    if (!status.isBandConnected()) {
                        status.setBandConnected(true);
                        statusChanged = true;
                        log.info("用户 {} 的频谱数据流已连接", userId);
                    }
                    status.setLastBandData(utcEventTime);
                    status.setBandPacketCount(status.getBandPacketCount() + 1);
                    break;

                case "TimeSeriesRaw_DISCONNECTED":
                    status.setRawConnected(false);
                    statusChanged = true;
                    log.info("用户 {} 的原始数据流已断开", userId);
                    break;

                case "TimeSeriesFilt_DISCONNECTED":
                    status.setFiltConnected(false);
                    statusChanged = true;
                    log.info("用户 {} 的滤波数据流已断开", userId);
                    break;

                case "AvgBandPower_DISCONNECTED":
                    status.setBandConnected(false);
                    statusChanged = true;
                    log.info("用户 {} 的频谱数据流已断开", userId);
                    break;
            }

            // 如果状态有变化，通知前端
            if (statusChanged) {
                webSocketService.notifyUser(userId, "CONNECTION_STATUS_CHANGED", status);
            }

            // 定期发送状态更新（每接收100个数据包）
            long totalPackets = status.getRawPacketCount() + status.getFiltPacketCount() + status.getBandPacketCount();
            if (totalPackets % 100 == 0) {
                webSocketService.notifyUser(userId, "STATUS_UPDATE", status);
            }
        }
    }

    // ... 其他辅助方法保持不变 ...

    /**
     * 获取用户的连接状态
     */
    public EEGConnectionStatus getConnectionStatus(Long userId) {
        return userConnections.get(userId);
    }

    /**
     * 获取所有活跃的连接状态
     */
    public ConcurrentHashMap<Long, EEGConnectionStatus> getAllConnectionStatuses() {
        return userConnections;
    }

    /**
     * 检查用户是否有活跃连接
     */
    public boolean isUserConnected(Long userId) {
        EEGConnectionStatus status = userConnections.get(userId);
        return status != null && (status.isRawConnected() || status.isFiltConnected() || status.isBandConnected());
    }

    /**
     * 获取用户特定数据流的数据包计数
     */
    public long getPacketCount(Long userId, String dataType) {
        String key = userId + "_" + dataType;
        AtomicLong counter = packetCounters.get(key);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 获取系统统计信息
     */
    public SystemStatistics getSystemStatistics() {
        SystemStatistics stats = new SystemStatistics();
        stats.activeConnections = userConnections.size();
        stats.activeSockets = activeSockets.size();
        stats.totalPacketsReceived = packetCounters.values().stream()
                .mapToLong(AtomicLong::get)
                .sum();

        for (EEGConnectionStatus status : userConnections.values()) {
            if (status.isRawConnected()) stats.activeRawStreams++;
            if (status.isFiltConnected()) stats.activeFiltStreams++;
            if (status.isBandConnected()) stats.activeBandStreams++;
        }

        return stats;
    }

    /**
     * 【关键修复】获取当前准确的UTC时间
     */
    private LocalDateTime getCurrentUtcTime() {
        // 使用Instant确保获取的是准确的UTC时间
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    /**
     * 【关键修复】将LocalDateTime转换为纳秒时间戳
     * 注意：这里的LocalDateTime必须已经是UTC时间
     */
    private long convertLocalDateTimeToNanos(LocalDateTime utcDateTime) {
        // 直接将UTC时间转换为纳秒时间戳，不进行任何时区转换
        return utcDateTime.atZone(ZoneOffset.UTC).toInstant().toEpochMilli() * 1000000L;
    }

    /**
     * 验证时间戳转换的工具方法（用于调试）
     */
    public void verifyTimestampConversion() {
        LocalDateTime utcNow = getCurrentUtcTime();
        long nanos = convertLocalDateTimeToNanos(utcNow);

        // 同时输出系统时间戳进行对比
        long systemMillis = System.currentTimeMillis();
        long instantMillis = Instant.now().toEpochMilli();

        log.info("时间戳转换验证:");
        log.info("UTC时间: {}", utcNow.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        log.info("纳秒时间戳: {}", nanos);
        log.info("转换回毫秒: {}", nanos / 1000000L);
        log.info("System.currentTimeMillis(): {}", systemMillis);
        log.info("Instant.now().toEpochMilli(): {}", instantMillis);

        // 验证差异（应该在几毫秒内）
        long diff = Math.abs((nanos / 1000000L) - instantMillis);
        if (diff > 1000) {
            log.warn("时间戳转换可能存在问题，差异: {}ms", diff);
        } else {
            log.info("时间戳转换正常，差异: {}ms", diff);
        }
    }

    /**
     * 系统统计信息
     */
    @Data
    public static class SystemStatistics {
        private int activeConnections = 0;
        private int activeSockets = 0;
        private int activeRawStreams = 0;
        private int activeFiltStreams = 0;
        private int activeBandStreams = 0;
        private long totalPacketsReceived = 0;
    }

    /**
     * 【新增】强制清理所有用户连接的方法（用于系统维护）
     */
    public void forceCleanupAllConnections() {
        log.warn("开始强制清理所有用户连接");

        // 获取所有用户ID
        Set<Long> allUserIds = new ConcurrentHashMap<>(userConnections).keySet();

        for (Long userId : allUserIds) {
            try {
                log.info("强制清理用户 {} 的连接", userId);
                stopListeningForUser(userId, "系统强制清理");
            } catch (Exception e) {
                log.error("强制清理用户 {} 连接时出错", userId, e);
            }
        }

        // 最终确保所有资源清理
        activeSockets.clear();
        userConnections.clear();
        userPortsMap.clear();
        packetCounters.clear();
        userDisconnectFlags.clear();

        log.warn("强制清理所有用户连接完成");
    }
}