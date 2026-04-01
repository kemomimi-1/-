//  增强的端口池管理服务
package com.eeg.service;

import lombok.extern.slf4j.Slf4j;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Service
public class EnhancedPortAllocationService {

    // 从配置文件读取端口范围
    @Value("${eeg.port-pool.start:15001}")
    private int portRangeStart;

    @Value("${eeg.port-pool.end:65535}")
    private int portRangeEnd;

    @Value("${eeg.port-pool.ports-per-user:3}")
    private int portsPerUser;

    @Value("${eeg.port-pool.reservation-timeout-minutes:30}")
    private int reservationTimeoutMinutes;

    // 端口池状态管理
    private final ConcurrentHashMap<Long, PortAllocation> userPortAllocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, PortReservation> portReservations = new ConcurrentHashMap<>();
    private final Set<Integer> blacklistedPorts = ConcurrentHashMap.newKeySet();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // 统计信息
    private final AtomicInteger totalAllocations = new AtomicInteger(0);
    private final AtomicInteger currentActiveAllocations = new AtomicInteger(0);

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PortAllocation {
        private Long userId;
        private int rawPort;        // TimeSeriesRaw 端口
        private int filtPort;       // TimeSeriesFilt 端口  
        private int bandPort;       // AvgBandPower 端口
        private LocalDateTime allocatedAt;
        private LocalDateTime lastUsedAt;
        private boolean active;
        private String sessionId;
        private AllocationStatus status;
        private String notes;

        public enum AllocationStatus {
            ALLOCATED,    // 已分配，等待使用
            ACTIVE,       // 正在使用中
            IDLE,         // 空闲状态
            EXPIRED,      // 已过期
            RELEASED      // 已释放
        }

        // 获取所有端口号的列表
        public List<Integer> getAllPorts() {
            return Arrays.asList(rawPort, filtPort, bandPort);
        }

        // 检查是否包含指定端口
        public boolean containsPort(int port) {
            return port == rawPort || port == filtPort || port == bandPort;
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PortReservation {
        private int port;
        private Long userId;
        private LocalDateTime reservedAt;
        private LocalDateTime expiresAt;
        private String purpose; // "raw", "filt", "band"
    }

    /**
     * 为用户分配端口组
     */
    public PortAllocation allocatePortsForUser(Long userId, String sessionId) {
        lock.writeLock().lock();
        try {
            log.info("开始为用户 {} 分配端口组，会话ID: {}", userId, sessionId);

            // 检查用户是否已有活跃的端口分配
            PortAllocation existingAllocation = userPortAllocations.get(userId);
            if (existingAllocation != null && existingAllocation.isActive()) {
                log.info("用户 {} 已有活跃端口分配，更新会话ID", userId);
                existingAllocation.setSessionId(sessionId);
                existingAllocation.setLastUsedAt(LocalDateTime.now());
                existingAllocation.setStatus(PortAllocation.AllocationStatus.ACTIVE);
                return existingAllocation;
            }

            // 查找可用的连续端口组
            int[] availablePorts = findAvailablePortGroup();
            if (availablePorts == null) {
                throw new RuntimeException("端口池已满，无法分配新的端口组");
            }

            // 创建端口分配
            PortAllocation allocation = new PortAllocation();
            allocation.setUserId(userId);
            allocation.setRawPort(availablePorts[0]);
            allocation.setFiltPort(availablePorts[1]);
            allocation.setBandPort(availablePorts[2]);
            allocation.setAllocatedAt(LocalDateTime.now());
            allocation.setLastUsedAt(LocalDateTime.now());
            allocation.setActive(true);
            allocation.setSessionId(sessionId);
            allocation.setStatus(PortAllocation.AllocationStatus.ALLOCATED);
            allocation.setNotes("新分配的端口组");

            // 预留端口
            reservePorts(availablePorts, userId);

            // 保存分配信息
            userPortAllocations.put(userId, allocation);

            // 更新统计
            totalAllocations.incrementAndGet();
            currentActiveAllocations.incrementAndGet();

            log.info("成功为用户 {} 分配端口组: Raw={}, Filt={}, Band={}",
                    userId, allocation.getRawPort(), allocation.getFiltPort(), allocation.getBandPort());

            return allocation;

        } catch (Exception e) {
            log.error("为用户 {} 分配端口组失败", userId, e);
            throw new RuntimeException("端口分配失败: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 查找可用的连续端口组
     */
    private int[] findAvailablePortGroup() {
        int searchStart = portRangeStart;
        int searchEnd = portRangeEnd - portsPerUser + 1;

        for (int basePort = searchStart; basePort <= searchEnd; basePort += portsPerUser) {
            int[] portGroup = {basePort, basePort + 1, basePort + 2};

            if (isPortGroupAvailable(portGroup)) {
                return portGroup;
            }
        }

        // 如果连续分配失败，尝试非连续分配
        return findNonConsecutivePortGroup();
    }

    /**
     * 查找非连续的可用端口组
     */
    private int[] findNonConsecutivePortGroup() {
        List<Integer> availablePorts = new ArrayList<>();

        for (int port = portRangeStart; port <= portRangeEnd; port++) {
            if (isPortAvailable(port)) {
                availablePorts.add(port);
                if (availablePorts.size() >= portsPerUser) {
                    break;
                }
            }
        }

        if (availablePorts.size() >= portsPerUser) {
            return new int[]{
                    availablePorts.get(0),
                    availablePorts.get(1),
                    availablePorts.get(2)
            };
        }

        return null;
    }

    /**
     * 检查端口组是否可用
     */
    private boolean isPortGroupAvailable(int[] ports) {
        for (int port : ports) {
            if (!isPortAvailable(port)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查单个端口是否可用
     */
    private boolean isPortAvailable(int port) {
        // 检查端口是否在有效范围内
        if (port < portRangeStart || port > portRangeEnd) {
            return false;
        }

        // 检查是否在黑名单中
        if (blacklistedPorts.contains(port)) {
            return false;
        }

        // 检查是否已被预留
        if (portReservations.containsKey(port)) {
            PortReservation reservation = portReservations.get(port);
            if (reservation.getExpiresAt().isAfter(LocalDateTime.now())) {
                return false;
            }
        }

        // 检查是否已被其他用户使用
        for (PortAllocation allocation : userPortAllocations.values()) {
            if (allocation.isActive() && allocation.containsPort(port)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 预留端口组
     */
    private void reservePorts(int[] ports, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(reservationTimeoutMinutes);

        String[] purposes = {"raw", "filt", "band"};

        for (int i = 0; i < ports.length; i++) {
            PortReservation reservation = new PortReservation(
                    ports[i], userId, now, expiresAt, purposes[i]
            );
            portReservations.put(ports[i], reservation);
        }

        log.debug("为用户 {} 预留端口: {}", userId, Arrays.toString(ports));
    }

    /**
     * 激活端口分配（当用户开始传输数据时调用）
     */
    public void activatePortAllocation(Long userId) {
        lock.writeLock().lock();
        try {
            PortAllocation allocation = userPortAllocations.get(userId);
            if (allocation != null) {
                allocation.setStatus(PortAllocation.AllocationStatus.ACTIVE);
                allocation.setLastUsedAt(LocalDateTime.now());

                // 移除端口预留，因为已经开始使用
                allocation.getAllPorts().forEach(portReservations::remove);

                log.info("激活用户 {} 的端口分配", userId);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 释放用户的端口分配
     */
    public void releasePortsForUser(Long userId, String reason) {
        lock.writeLock().lock();
        try {
            PortAllocation allocation = userPortAllocations.get(userId);
            if (allocation != null) {
                allocation.setActive(false);
                allocation.setStatus(PortAllocation.AllocationStatus.RELEASED);
                allocation.setNotes(reason);

                // 移除端口预留
                allocation.getAllPorts().forEach(portReservations::remove);

                // 更新统计
                currentActiveAllocations.decrementAndGet();

                log.info("释放用户 {} 的端口分配: Raw={}, Filt={}, Band={}, 原因: {}",
                        userId, allocation.getRawPort(), allocation.getFiltPort(),
                        allocation.getBandPort(), reason);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取用户的端口分配
     */
    public PortAllocation getUserPortAllocation(Long userId) {
        return userPortAllocations.get(userId);
    }

    /**
     * 检查用户是否有活跃的端口分配
     */
    public boolean hasActivePortAllocation(Long userId) {
        PortAllocation allocation = userPortAllocations.get(userId);
        return allocation != null && allocation.isActive() &&
                allocation.getStatus() == PortAllocation.AllocationStatus.ACTIVE;
    }

    /**
     * 获取端口池统计信息
     */
    public PortPoolStatistics getPortPoolStatistics() {
        lock.readLock().lock();
        try {
            int totalPorts = portRangeEnd - portRangeStart + 1;
            int usedPorts = 0;
            int reservedPorts = portReservations.size();
            int blacklistedPortsCount = blacklistedPorts.size();

            for (PortAllocation allocation : userPortAllocations.values()) {
                if (allocation.isActive()) {
                    usedPorts += portsPerUser;
                }
            }

            int availablePorts = totalPorts - usedPorts - reservedPorts - blacklistedPortsCount;
            double utilizationRate = (double) usedPorts / totalPorts * 100;

            return new PortPoolStatistics(
                    totalPorts, usedPorts, availablePorts, reservedPorts, blacklistedPortsCount,
                    currentActiveAllocations.get(), totalAllocations.get(), utilizationRate
            );

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 添加端口到黑名单
     */
    public void addPortToBlacklist(int port, String reason) {
        blacklistedPorts.add(port);
        log.warn("端口 {} 已添加到黑名单，原因: {}", port, reason);
    }

    /**
     * 从黑名单移除端口
     */
    public void removePortFromBlacklist(int port) {
        blacklistedPorts.remove(port);
        log.info("端口 {} 已从黑名单移除", port);
    }

    /**
     * 获取所有活跃的端口分配
     */
    public Map<Long, PortAllocation> getAllActiveAllocations() {
        lock.readLock().lock();
        try {
            Map<Long, PortAllocation> activeAllocations = new HashMap<>();
            for (Map.Entry<Long, PortAllocation> entry : userPortAllocations.entrySet()) {
                if (entry.getValue().isActive()) {
                    activeAllocations.put(entry.getKey(), entry.getValue());
                }
            }
            return activeAllocations;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 定期清理过期的端口预留和分配
     */
    @Scheduled(fixedRate = 60000) // 每分钟检查一次
    public void cleanupExpiredReservationsAndAllocations() {
        lock.writeLock().lock();
        try {
            LocalDateTime now = LocalDateTime.now();

            // 清理过期的端口预留
            portReservations.entrySet().removeIf(entry -> {
                if (entry.getValue().getExpiresAt().isBefore(now)) {
                    log.debug("清理过期的端口预留: 端口={}, 用户={}",
                            entry.getKey(), entry.getValue().getUserId());
                    return true;
                }
                return false;
            });

            // 清理长时间未使用的端口分配
            LocalDateTime expireThreshold = now.minusMinutes(reservationTimeoutMinutes * 2);
            userPortAllocations.entrySet().removeIf(entry -> {
                PortAllocation allocation = entry.getValue();
                if (allocation.getStatus() == PortAllocation.AllocationStatus.RELEASED ||
                        (!allocation.isActive() && allocation.getLastUsedAt().isBefore(expireThreshold))) {

                    log.info("清理过期的端口分配: 用户={}, 端口组=[{},{},{}]",
                            entry.getKey(), allocation.getRawPort(),
                            allocation.getFiltPort(), allocation.getBandPort());

                    if (allocation.isActive()) {
                        currentActiveAllocations.decrementAndGet();
                    }
                    return true;
                }
                return false;
            });

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 端口池统计信息类
     */
    @Data
    @AllArgsConstructor
    public static class PortPoolStatistics {
        private int totalPorts;
        private int usedPorts;
        private int availablePorts;
        private int reservedPorts;
        private int blacklistedPorts;
        private int activeAllocations;
        private int totalAllocationsMade;
        private double utilizationRate;
    }

    /**
     * 强制清理用户端口（管理员功能）
     */
    public void forceReleaseUserPorts(Long userId, String adminReason) {
        lock.writeLock().lock();
        try {
            PortAllocation allocation = userPortAllocations.get(userId);
            if (allocation != null) {
                releasePortsForUser(userId, "管理员强制释放: " + adminReason);
                log.warn("管理员强制释放用户 {} 的端口分配，原因: {}", userId, adminReason);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取端口使用历史（仅返回当前分配信息，无需持久化）
     */
    public List<PortAllocation> getPortAllocationHistory(Long userId, int limit) {
        // 端口信息是临时资源，无需存储历史记录
        PortAllocation current = userPortAllocations.get(userId);
        return current != null ? List.of(current) : List.of();
    }
}