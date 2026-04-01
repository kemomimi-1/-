// 增强事务处理和并发控制的版本
package com.eeg.service;

import com.eeg.entity.EEGSession;
import com.eeg.repository.EEGSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EEGSessionService {

    private final EEGSessionRepository sessionRepository;
    private final WebSocketNotificationService webSocketService;
    private final EnhancedPortAllocationService portService;

    // 内存中的会话状态缓存
    private final ConcurrentHashMap<Long, EEGSession> activeSessionsCache = new ConcurrentHashMap<>();

    // 数据流暂停检测的时间阈值（秒）
    private static final long STREAM_PAUSE_THRESHOLD_SECONDS = 30;
    private static final long SESSION_TIMEOUT_MINUTES = 60;

    /**
     * 开始新的数据传输会话 - 修复时区处理
     */
    @Transactional
    public EEGSession startNewSession(Long userId, String userTimezone,
                                      Integer rawPort, Integer filtPort, Integer bandPort) {
        log.info("为用户 {} 开始新的EEG数据传输会话", userId);

        // 检查是否有活跃会话，如果有则先结束
        Optional<EEGSession> existingSession = getActiveSession(userId);
        if (existingSession.isPresent()) {
            log.warn("用户 {} 已有活跃会话，先结束旧会话", userId);
            endSession(existingSession.get().getId(), "新会话开始，自动结束旧会话");
        }

        // 【关键修复】获取准确的UTC时间
        LocalDateTime utcNow = getCurrentUtcTime();

        log.info("创建会话时间 - UTC: {}, 用户时区: {}",
                utcNow.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), userTimezone);

        // 获取用户的端口分配信息
        EnhancedPortAllocationService.PortAllocation portAllocation =
                portService.getUserPortAllocation(userId);

        if (portAllocation == null) {
            throw new RuntimeException("用户没有有效的端口分配，请先申请连接");
        }

        // 创建新会话 - 【关键修复】所有时间字段都使用UTC
        EEGSession session = new EEGSession();
        session.setUserId(userId);
        session.setUserTimezone(userTimezone);

        // 【关键修复】：统一使用UTC时间，不进行任何时区转换
        session.setSessionStartTimeUtc(utcNow);
        session.setSessionStartTime(utcNow); // 这个字段也存储UTC时间，前端负责时区转换
        session.setSessionStatus(EEGSession.SessionStatus.ACTIVE);

        session.setRawPort(rawPort);
        session.setFiltPort(filtPort);
        session.setBandPort(bandPort);

        // 保存会话并缓存
        EEGSession savedSession = sessionRepository.save(session);
        activeSessionsCache.put(userId, savedSession);

        log.info("会话已保存 - UTC时间: {}",
                savedSession.getSessionStartTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        // 激活端口分配
        try {
            portService.activatePortAllocation(userId);
            log.info("已激活用户 {} 的端口分配", userId);
        } catch (Exception e) {
            log.error("激活用户 {} 端口分配失败", userId, e);
        }

        // 通知前端会话开始
        webSocketService.notifyUser(userId, "SESSION_STARTED",
                createSessionNotificationData(savedSession, portAllocation));

        log.info("用户 {} 的新会话已创建，会话ID: {}, 端口组: Raw={}, Filt={}, Band={}",
                userId, savedSession.getId(), rawPort, filtPort, bandPort);

        return savedSession;
    }

    /**
     * 处理数据包到达事件 - 【关键修复】确保时间处理正确
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void handleDataPacketReceived(Long userId, String streamType, LocalDateTime packetTimeUtc) {
        EEGSession session = getOrCreateActiveSession(userId);
        if (session == null) {
            log.warn("用户 {} 没有活跃会话，无法处理数据包", userId);
            return;
        }

        // 【关键修复】：确保传入的时间是UTC时间，不进行任何转换
        LocalDateTime utcTime = packetTimeUtc != null ? packetTimeUtc : getCurrentUtcTime();
        boolean sessionUpdated = false;

        log.debug("处理数据包 - 用户:{}, 类型:{}, UTC时间:{}",
                userId, streamType, utcTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        switch (streamType.toLowerCase()) {
            case "timeseriesraw":
                sessionUpdated = updateStreamStatus(session, "raw", utcTime);
                break;
            case "timeseriesfilt":
                sessionUpdated = updateStreamStatus(session, "filt", utcTime);
                break;
            case "avgbandpower":
                sessionUpdated = updateStreamStatus(session, "band", utcTime);
                break;
            default:
                log.warn("未知的数据流类型: {}", streamType);
                return;
        }

        if (sessionUpdated) {
            session.setUpdatedAt(getCurrentUtcTime()); // 使用UTC时间
            EEGSession updatedSession = sessionRepository.save(session);
            activeSessionsCache.put(userId, updatedSession);

            // 通知前端会话状态更新
            webSocketService.notifyUser(userId, "SESSION_UPDATED",
                    createSessionNotificationData(updatedSession, null));
        }
    }

    /**
     * 更新特定数据流的状态 - 优化保存频率版本
     * 批量保存策略：每100个数据包或每30秒保存一次，状态变化时立即保存
     */
    private boolean updateStreamStatus(EEGSession session, String streamType, LocalDateTime utcPacketTime) {
        boolean updated = false;

        // 添加会话级别的批量保存控制
        if (session.getUpdatedAt() == null) {
            session.setUpdatedAt(utcPacketTime);
        }

        log.debug("更新数据流状态 - 类型:{}, UTC时间:{}",
                streamType, utcPacketTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        switch (streamType) {
            case "raw":
                // 状态变化时立即保存
                if (session.getRawStreamStatus() == EEGSession.StreamStatus.WAITING) {
                    session.setRawStreamStartTimeUtc(utcPacketTime);
                    session.setRawStreamStatus(EEGSession.StreamStatus.ACTIVE);
                    updated = true; // 状态变化，立即保存
                    log.info("会话 {} 的原始数据流开始传输 - UTC时间: {}",
                            session.getId(), utcPacketTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                } else if (session.getRawStreamStatus() == EEGSession.StreamStatus.PAUSED) {
                    session.setRawStreamStatus(EEGSession.StreamStatus.ACTIVE);
                    updated = true; // 状态变化，立即保存
                    log.info("会话 {} 的原始数据流恢复传输", session.getId());
                }

                // 更新时间和计数
                session.setRawStreamLastPacketTimeUtc(utcPacketTime);
                Long rawCount = session.getRawStreamTotalPackets();
                session.setRawStreamTotalPackets(rawCount != null ? rawCount + 1 : 1L);

                // 批量保存策略：每100个数据包保存一次
                if (session.getRawStreamTotalPackets() % 100 == 0) {
                    updated = true;
                    log.debug("Raw流达到批量保存阈值: {} 个数据包", session.getRawStreamTotalPackets());
                }

                break;

            case "filt":
                // 状态变化时立即保存
                if (session.getFiltStreamStatus() == EEGSession.StreamStatus.WAITING) {
                    session.setFiltStreamStartTimeUtc(utcPacketTime);
                    session.setFiltStreamStatus(EEGSession.StreamStatus.ACTIVE);
                    updated = true;
                    log.info("会话 {} 的滤波数据流开始传输 - UTC时间: {}",
                            session.getId(), utcPacketTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                } else if (session.getFiltStreamStatus() == EEGSession.StreamStatus.PAUSED) {
                    session.setFiltStreamStatus(EEGSession.StreamStatus.ACTIVE);
                    updated = true;
                    log.info("会话 {} 的滤波数据流恢复传输", session.getId());
                }

                session.setFiltStreamLastPacketTimeUtc(utcPacketTime);
                Long filtCount = session.getFiltStreamTotalPackets();
                session.setFiltStreamTotalPackets(filtCount != null ? filtCount + 1 : 1L);

                // 批量保存策略：每100个数据包保存一次
                if (session.getFiltStreamTotalPackets() % 100 == 0) {
                    updated = true;
                    log.debug("Filt流达到批量保存阈值: {} 个数据包", session.getFiltStreamTotalPackets());
                }

                break;

            case "band":
                // 状态变化时立即保存
                if (session.getBandStreamStatus() == EEGSession.StreamStatus.WAITING) {
                    session.setBandStreamStartTimeUtc(utcPacketTime);
                    session.setBandStreamStatus(EEGSession.StreamStatus.ACTIVE);
                    updated = true;
                    log.info("会话 {} 的频谱数据流开始传输 - UTC时间: {}",
                            session.getId(), utcPacketTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                } else if (session.getBandStreamStatus() == EEGSession.StreamStatus.PAUSED) {
                    session.setBandStreamStatus(EEGSession.StreamStatus.ACTIVE);
                    updated = true;
                    log.info("会话 {} 的频谱数据流恢复传输", session.getId());
                }

                session.setBandStreamLastPacketTimeUtc(utcPacketTime);
                Long bandCount = session.getBandStreamTotalPackets();
                session.setBandStreamTotalPackets(bandCount != null ? bandCount + 1 : 1L);

                // 批量保存策略：每100个数据包保存一次
                if (session.getBandStreamTotalPackets() % 100 == 0) {
                    updated = true;
                    log.debug("Band流达到批量保存阈值: {} 个数据包", session.getBandStreamTotalPackets());
                }

                break;
        }

        // 时间间隔保存策略：每30秒保存一次（防止长时间不保存）
        LocalDateTime now = getCurrentUtcTime();
        if (!updated && session.getUpdatedAt() != null) {
            long secondsSinceLastUpdate = java.time.Duration.between(session.getUpdatedAt(), now).getSeconds();
            if (secondsSinceLastUpdate >= 30) {
                updated = true;
                log.debug("会话 {} 达到时间间隔保存阈值: {}秒", session.getId(), secondsSinceLastUpdate);
            }
        }

        return updated;
    }

    /**
     * 【关键修复】强制结束用户的活跃会话 - 增强事务控制
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    public void forceEndUserSession(Long userId, String reason) {
        log.info("开始强制结束用户 {} 的会话，原因: {}", userId, reason);

        try {
            // 【关键修复】从数据库重新查询最新状态，避免缓存问题
            Optional<EEGSession> activeSessionOpt = sessionRepository.findByUserIdAndSessionStatus(
                    userId, EEGSession.SessionStatus.ACTIVE);

            if (activeSessionOpt.isPresent()) {
                EEGSession activeSession = activeSessionOpt.get();
                log.info("找到用户 {} 的活跃会话: {}", userId, activeSession.getId());

                // 【关键修复】立即从缓存中移除，防止并发访问
                activeSessionsCache.remove(userId);

                // 结束会话
                endSessionWithLock(activeSession.getId(), reason);
                log.info("成功结束用户 {} 的会话: {}", userId, activeSession.getId());
            } else {
                log.info("用户 {} 没有找到活跃会话，检查缓存", userId);

                // 检查缓存中是否有会话
                EEGSession cachedSession = activeSessionsCache.remove(userId);
                if (cachedSession != null) {
                    log.warn("发现缓存中的会话 {} 与数据库不一致，强制结束", cachedSession.getId());
                    try {
                        endSessionWithLock(cachedSession.getId(), reason + " (缓存清理)");
                    } catch (Exception e) {
                        log.error("清理缓存会话时出错", e);
                    }
                }
            }

            // 【关键修复】确保释放端口分配
            try {
                portService.releasePortsForUser(userId, "强制清理: " + reason);
                log.info("已释放用户 {} 的端口分配", userId);
            } catch (Exception e) {
                log.error("释放用户 {} 端口分配失败", userId, e);
            }

        } catch (Exception e) {
            log.error("强制结束用户 {} 会话时发生异常", userId, e);
            throw e; // 重新抛出异常，让调用方知道操作失败
        }
    }

    /**
     * 【新增】带锁的结束会话方法 - 防止并发问题
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    public void endSessionWithLock(Long sessionId, String reason) {
        Optional<EEGSession> sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            log.warn("尝试结束不存在的会话: {}", sessionId);
            return;
        }

        EEGSession session = sessionOpt.get();

        // 【关键修复】检查会话是否已经结束
        if (session.getSessionStatus() != EEGSession.SessionStatus.ACTIVE) {
            log.info("会话 {} 已经不是活跃状态: {}", sessionId, session.getSessionStatus());
            return;
        }

        LocalDateTime utcNow = getCurrentUtcTime();

        log.info("结束会话 {} - UTC时间: {}", sessionId,
                utcNow.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        // 【关键修复】：设置结束时间 - 统一使用UTC时间
        session.setSessionEndTimeUtc(utcNow);
        session.setSessionEndTime(utcNow); // 这个字段也存储UTC时间，前端负责时区转换
        session.setSessionStatus(EEGSession.SessionStatus.COMPLETED);
        session.setTotalDurationSeconds(session.calculateDurationSeconds());
        session.setNotes(reason);

        // 结束所有还在活跃状态的数据流
        if (session.getRawStreamStatus() == EEGSession.StreamStatus.ACTIVE) {
            session.setRawStreamStatus(EEGSession.StreamStatus.COMPLETED);
            session.setRawStreamEndTimeUtc(utcNow);
        }
        if (session.getFiltStreamStatus() == EEGSession.StreamStatus.ACTIVE) {
            session.setFiltStreamStatus(EEGSession.StreamStatus.COMPLETED);
            session.setFiltStreamEndTimeUtc(utcNow);
        }
        if (session.getBandStreamStatus() == EEGSession.StreamStatus.ACTIVE) {
            session.setBandStreamStatus(EEGSession.StreamStatus.COMPLETED);
            session.setBandStreamEndTimeUtc(utcNow);
        }

        // 【新增】强制记录最终数据包计数
        forceSaveFinalPacketCounts(session);

        // 保存会话状态
        EEGSession savedSession = sessionRepository.save(session);

        // 【关键修复】确保从缓存中移除
        activeSessionsCache.remove(session.getUserId());

        log.info("会话 {} 已保存结束状态 - UTC结束时间: {}", sessionId,
                savedSession.getSessionEndTimeUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        // 释放端口分配
        try {
            portService.releasePortsForUser(session.getUserId(), "会话结束: " + reason);
            log.info("已释放用户 {} 的端口分配", session.getUserId());
        } catch (Exception e) {
            log.error("释放用户 {} 端口分配失败", session.getUserId(), e);
        }

        // 通知前端会话结束
        webSocketService.notifyUser(session.getUserId(), "SESSION_ENDED",
                createSessionNotificationData(savedSession, null));

        log.info("会话 {} 已结束，原因: {}, 持续时间: {}秒",
                sessionId, reason, savedSession.getTotalDurationSeconds());
    }

    /**
     * 结束会话 - 【关键修复】统一使用UTC时间并强制保存最终计数
     */
    @Transactional
    public void endSession(Long sessionId, String reason) {
        endSessionWithLock(sessionId, reason);
    }

    /**
     * 强制记录最终的数据包计数，确保准确性
     * 这个方法主要用于调试和监控，确保会话结束时的计数被正确记录
     */
    private void forceSaveFinalPacketCounts(EEGSession session) {
        // 获取最终计数
        Long rawPackets = session.getRawStreamTotalPackets() != null ? session.getRawStreamTotalPackets() : 0L;
        Long filtPackets = session.getFiltStreamTotalPackets() != null ? session.getFiltStreamTotalPackets() : 0L;
        Long bandPackets = session.getBandStreamTotalPackets() != null ? session.getBandStreamTotalPackets() : 0L;
        Long totalPackets = rawPackets + filtPackets + bandPackets;

        // 记录详细的最终统计
        log.info("会话 {} 结束时数据包统计:", session.getId());
        log.info("  - 原始数据流 (TimeSeriesRaw): {} 个数据包", rawPackets);
        log.info("  - 滤波数据流 (TimeSeriesFilt): {} 个数据包", filtPackets);
        log.info("  - 频谱数据流 (AvgBandPower): {} 个数据包", bandPackets);
        log.info("  - 总计: {} 个数据包", totalPackets);
        log.info("  - 会话持续时间: {} 秒", session.calculateDurationSeconds());

        // 计算数据传输速率
        long durationSeconds = session.calculateDurationSeconds();
        if (durationSeconds > 0) {
            double packetsPerSecond = (double) totalPackets / durationSeconds;
            log.info("  - 平均数据包接收率: {:.2f} 包/秒", packetsPerSecond);
        }

        // 检查数据流的完整性
        boolean hasRawData = rawPackets > 0;
        boolean hasFiltData = filtPackets > 0;
        boolean hasBandData = bandPackets > 0;

        log.info("  - 数据完整性检查: Raw={}, Filt={}, Band={}",
                hasRawData ? "有数据" : "无数据",
                hasFiltData ? "有数据" : "无数据",
                hasBandData ? "有数据" : "无数据");

        // 如果总数据包为0，记录警告
        if (totalPackets == 0) {
            log.warn("警告：会话 {} 没有接收到任何数据包！", session.getId());
        }
    }

    /**
     * 获取用户真正最新的会话（按创建时间排序）
     */
    public Optional<EEGSession> getUserMostRecentSession(Long userId) {
        try {
            PageRequest pageRequest = PageRequest.of(0, 1);
            List<EEGSession> sessions = sessionRepository.findUserMostRecentSessions(userId, pageRequest);

            if (!sessions.isEmpty()) {
                EEGSession mostRecentSession = sessions.get(0);
                log.info("获取用户 {} 最新会话: ID={}, 创建时间={}, UTC开始时间={}, 状态={}",
                        userId, mostRecentSession.getId(),
                        mostRecentSession.getCreatedAt(),
                        mostRecentSession.getSessionStartTimeUtc(),
                        mostRecentSession.getSessionStatus());
                return Optional.of(mostRecentSession);
            }

            log.debug("用户 {} 没有找到任何会话", userId);
            return Optional.empty();

        } catch (Exception e) {
            log.error("获取用户 {} 最新会话时出错", userId, e);
            return Optional.empty();
        }
    }

    /**
     * 调试方法：打印用户所有会话的时间信息
     */
    public void debugUserSessionTimes(Long userId) {
        log.info("=== 调试用户 {} 的会话时间信息 ===", userId);
        List<EEGSession> sessions = getUserSessionHistory(userId, 10);

        for (EEGSession session : sessions) {
            log.info("会话ID: {}", session.getId());
            log.info("  创建时间(本地): {}", session.getCreatedAt());
            log.info("  UTC开始时间: {}", session.getSessionStartTimeUtc());
            log.info("  UTC结束时间: {}", session.getSessionEndTimeUtc());
            log.info("  用户时区: {}", session.getUserTimezone());
            log.info("  状态: {}", session.getSessionStatus());
            log.info("  ---");
        }
        log.info("=== 调试信息结束 ===");
    }

    /**
     * 获取用户的活跃会话
     */
    public Optional<EEGSession> getActiveSession(Long userId) {
        // 先从缓存中查找
        EEGSession cachedSession = activeSessionsCache.get(userId);
        if (cachedSession != null && cachedSession.isActive()) {
            return Optional.of(cachedSession);
        }

        // 从数据库查找
        Optional<EEGSession> dbSession = sessionRepository.findByUserIdAndSessionStatus(
                userId, EEGSession.SessionStatus.ACTIVE);

        if (dbSession.isPresent()) {
            activeSessionsCache.put(userId, dbSession.get());
        } else {
            activeSessionsCache.remove(userId);
        }

        return dbSession;
    }

    /**
     * 获取或创建活跃会话
     */
    private EEGSession getOrCreateActiveSession(Long userId) {
        Optional<EEGSession> session = getActiveSession(userId);
        return session.orElse(null);
    }

    /**
     * 获取用户会话历史
     */
    public List<EEGSession> getUserSessionHistory(Long userId, int limit) {
        PageRequest pageRequest = PageRequest.of(0, limit);
        return sessionRepository.findUserRecentSessions(userId, pageRequest);
    }

    /**
     * 获取用户最新完成的会话
     */
    public Optional<EEGSession> getUserLatestCompletedSession(Long userId) {
        PageRequest pageRequest = PageRequest.of(0, 1);
        List<EEGSession> sessions = sessionRepository.findUserLatestCompletedSessions(userId, pageRequest);
        return sessions.isEmpty() ? Optional.empty() : Optional.of(sessions.get(0));
    }

    /**
     * 在指定时间范围内查找用户会话 - 基于UTC时间
     */
    public List<EEGSession> getUserSessionsInTimeRange(Long userId, LocalDateTime startTimeUtc, LocalDateTime endTimeUtc) {
        return sessionRepository.findUserSessionsByTimeRange(userId, startTimeUtc, endTimeUtc);
    }

    /**
     * 获取用户在指定时间点的活跃会话 - 基于UTC时间
     */
    public Optional<EEGSession> getUserSessionAtTime(Long userId, LocalDateTime timePointUtc) {
        return sessionRepository.findUserSessionAtTime(userId, timePointUtc);
    }

    /**
     * 检测并更新数据流暂停状态 - 基于UTC时间
     */
    @Scheduled(fixedRate = 15000) // 每15秒检查一次
    @Transactional
    public void detectStreamPauses() {
        LocalDateTime thresholdUtc = getCurrentUtcTime().minusSeconds(STREAM_PAUSE_THRESHOLD_SECONDS);

        for (EEGSession session : activeSessionsCache.values()) {
            boolean sessionUpdated = false;

            // 检查原始数据流是否暂停
            if (session.getRawStreamStatus() == EEGSession.StreamStatus.ACTIVE &&
                    session.getRawStreamLastPacketTimeUtc() != null &&
                    session.getRawStreamLastPacketTimeUtc().isBefore(thresholdUtc)) {
                session.setRawStreamStatus(EEGSession.StreamStatus.PAUSED);
                sessionUpdated = true;
                log.info("检测到会话 {} 的原始数据流暂停", session.getId());
            }

            // 检查滤波数据流是否暂停
            if (session.getFiltStreamStatus() == EEGSession.StreamStatus.ACTIVE &&
                    session.getFiltStreamLastPacketTimeUtc() != null &&
                    session.getFiltStreamLastPacketTimeUtc().isBefore(thresholdUtc)) {
                session.setFiltStreamStatus(EEGSession.StreamStatus.PAUSED);
                sessionUpdated = true;
                log.info("检测到会话 {} 的滤波数据流暂停", session.getId());
            }

            // 检查频谱数据流是否暂停
            if (session.getBandStreamStatus() == EEGSession.StreamStatus.ACTIVE &&
                    session.getBandStreamLastPacketTimeUtc() != null &&
                    session.getBandStreamLastPacketTimeUtc().isBefore(thresholdUtc)) {
                session.setBandStreamStatus(EEGSession.StreamStatus.PAUSED);
                sessionUpdated = true;
                log.info("检测到会话 {} 的频谱数据流暂停", session.getId());
            }

            if (sessionUpdated) {
                EEGSession updatedSession = sessionRepository.save(session);
                activeSessionsCache.put(session.getUserId(), updatedSession);
                webSocketService.notifyUser(session.getUserId(), "STREAM_PAUSED",
                        createSessionNotificationData(updatedSession, null));
            }
        }
    }

    /**
     * 清理超时的活跃会话 - 基于UTC时间
     */
    @Scheduled(fixedRate = 300000) // 每5分钟检查一次
    @Transactional
    public void cleanupTimeoutSessions() {
        LocalDateTime timeoutThresholdUtc = getCurrentUtcTime().minusMinutes(SESSION_TIMEOUT_MINUTES);
        List<EEGSession> timeoutSessions = sessionRepository.findTimeoutActiveSessions(timeoutThresholdUtc);

        for (EEGSession session : timeoutSessions) {
            log.warn("会话 {} 超时，自动结束", session.getId());
            endSession(session.getId(), "会话超时自动结束");
        }
    }

    /**
     * UTC时间转换为用户本地时间
     */
    public LocalDateTime convertUtcToUserTime(LocalDateTime utcTime, String userTimezone) {
        if (userTimezone == null || userTimezone.isEmpty()) {
            return utcTime; // 如果没有时区信息，返回UTC时间
        }

        try {
            ZoneId userZone = ZoneId.of(userTimezone);
            ZonedDateTime utcZoned = utcTime.atZone(ZoneOffset.UTC);
            ZonedDateTime userZoned = utcZoned.withZoneSameInstant(userZone);
            return userZoned.toLocalDateTime();
        } catch (Exception e) {
            log.warn("时区转换失败，使用UTC时间: {}", e.getMessage());
            return utcTime;
        }
    }

    /**
     * 用户本地时间转换为UTC时间
     */
    public LocalDateTime convertUserTimeToUtc(LocalDateTime userTime, String userTimezone) {
        if (userTimezone == null || userTimezone.isEmpty()) {
            return userTime; // 如果没有时区信息，假设已经是UTC时间
        }

        try {
            ZoneId userZone = ZoneId.of(userTimezone);
            ZonedDateTime userZoned = userTime.atZone(userZone);
            ZonedDateTime utcZoned = userZoned.withZoneSameInstant(ZoneOffset.UTC);
            return utcZoned.toLocalDateTime();
        } catch (Exception e) {
            log.warn("时区转换失败，使用原始时间: {}", e.getMessage());
            return userTime;
        }
    }

    /**
     * 【关键修复】获取用户会话统计信息 - 修复数组越界问题
     */
    public SessionStatistics getUserSessionStatistics(Long userId) {
        try {
            // 直接使用分别查询的方式，避免复杂SQL导致的解析问题
            log.info("获取用户 {} 的会话统计信息", userId);

            Long totalSessions = sessionRepository.getTotalSessionsCount(userId);
            Long completedSessions = sessionRepository.getCompletedSessionsCount(userId);
            Long activeSessions = sessionRepository.getActiveSessionsCount(userId);
            Double avgDuration = sessionRepository.getAvgDurationSeconds(userId);
            Long totalRaw = sessionRepository.getTotalRawPackets(userId);
            Long totalFilt = sessionRepository.getTotalFiltPackets(userId);
            Long totalBand = sessionRepository.getTotalBandPackets(userId);

            log.debug("统计结果 - 总会话数: {}, 已完成: {}, 活跃: {}, 平均时长: {}s",
                    totalSessions, completedSessions, activeSessions, avgDuration);

            return new SessionStatistics(
                    totalSessions != null ? totalSessions : 0L,
                    completedSessions != null ? completedSessions : 0L,
                    activeSessions != null ? activeSessions : 0L,
                    avgDuration != null ? avgDuration : 0.0,
                    totalRaw != null ? totalRaw : 0L,
                    totalFilt != null ? totalFilt : 0L,
                    totalBand != null ? totalBand : 0L
            );

        } catch (Exception e) {
            log.error("获取用户 {} 会话统计失败", userId, e);
            return new SessionStatistics(0L, 0L, 0L, 0.0, 0L, 0L, 0L);
        }
    }

    /**
     * 【关键修复】获取当前准确的UTC时间
     */
    private LocalDateTime getCurrentUtcTime() {
        // 使用Instant确保获取的是准确的UTC时间
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    /**
     * 创建会话通知数据
     */
    private Object createSessionNotificationData(EEGSession session,
                                                 EnhancedPortAllocationService.PortAllocation portAllocation) {

        var sessionData = new java.util.HashMap<String, Object>();
        sessionData.put("session", session);

        if (portAllocation != null) {
            var portData = new java.util.HashMap<String, Object>();
            portData.put("rawPort", portAllocation.getRawPort());
            portData.put("filtPort", portAllocation.getFiltPort());
            portData.put("bandPort", portAllocation.getBandPort());
            portData.put("status", portAllocation.getStatus());
            sessionData.put("portAllocation", portData);
        }

        return sessionData;
    }

    /**
     * 会话统计信息数据类
     */
    public static class SessionStatistics {
        public final Long totalSessions;
        public final Long completedSessions;
        public final Long activeSessions;
        public final Double avgDurationSeconds;
        public final Long totalRawPackets;
        public final Long totalFiltPackets;
        public final Long totalBandPackets;

        public SessionStatistics(Long totalSessions, Long completedSessions, Long activeSessions,
                                 Double avgDurationSeconds, Long totalRawPackets, Long totalFiltPackets,
                                 Long totalBandPackets) {
            this.totalSessions = totalSessions;
            this.completedSessions = completedSessions;
            this.activeSessions = activeSessions;
            this.avgDurationSeconds = avgDurationSeconds;
            this.totalRawPackets = totalRawPackets;
            this.totalFiltPackets = totalFiltPackets;
            this.totalBandPackets = totalBandPackets;
        }
    }

    /**
     * 提供给AI查询的时间范围查询方法
     * 返回指定UTC时间范围内的会话数据，包含详细的时区信息
     */
    public List<SessionWithTimeContext> getSessionsForTimeRangeAnalysis(Long userId,
                                                                        LocalDateTime startTimeUtc,
                                                                        LocalDateTime endTimeUtc,
                                                                        String userTimezone) {
        List<EEGSession> sessions = getUserSessionsInTimeRange(userId, startTimeUtc, endTimeUtc);

        return sessions.stream()
                .map(session -> new SessionWithTimeContext(
                        session,
                        convertUtcToUserTime(session.getSessionStartTimeUtc(), userTimezone),
                        session.getSessionEndTimeUtc() != null ?
                                convertUtcToUserTime(session.getSessionEndTimeUtc(), userTimezone) : null,
                        userTimezone
                ))
                .toList();
    }

    /**
     * 会话时间上下文类 - 包含UTC和用户本地时间
     */
    public static class SessionWithTimeContext {
        public final EEGSession session;
        public final LocalDateTime startTimeLocal;
        public final LocalDateTime endTimeLocal;
        public final String userTimezone;

        public SessionWithTimeContext(EEGSession session,
                                      LocalDateTime startTimeLocal,
                                      LocalDateTime endTimeLocal,
                                      String userTimezone) {
            this.session = session;
            this.startTimeLocal = startTimeLocal;
            this.endTimeLocal = endTimeLocal;
            this.userTimezone = userTimezone;
        }
    }

    /**
     * 【新增】调试方法：检查缓存和数据库一致性
     */
    public void debugCacheConsistency() {
        log.info("开始检查缓存和数据库一致性");

        for (Long userId : activeSessionsCache.keySet()) {
            EEGSession cachedSession = activeSessionsCache.get(userId);
            Optional<EEGSession> dbSession = sessionRepository.findByUserIdAndSessionStatus(
                    userId, EEGSession.SessionStatus.ACTIVE);

            if (dbSession.isPresent()) {
                if (!cachedSession.getId().equals(dbSession.get().getId())) {
                    log.warn("用户 {} 的缓存会话ID {} 与数据库会话ID {} 不一致",
                            userId, cachedSession.getId(), dbSession.get().getId());
                }
            } else {
                log.warn("用户 {} 的缓存中有会话 {} 但数据库中无对应活跃会话",
                        userId, cachedSession.getId());
            }
        }

        log.info("缓存一致性检查完成");
    }

    /**
     * 【新增】强制清理所有缓存
     */
    public void forceClearAllCache() {
        log.warn("强制清理所有会话缓存");
        activeSessionsCache.clear();
        log.info("会话缓存已清理完成");
    }
}