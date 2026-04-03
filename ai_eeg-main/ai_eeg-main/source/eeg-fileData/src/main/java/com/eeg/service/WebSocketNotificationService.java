// WebSocket通知服务
package com.eeg.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketNotificationService {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    public void addUserSession(Long userId, WebSocketSession session) {
        userSessions.put(userId, session);
        log.info("用户 {} WebSocket会话已连接", userId);
    }

    public void removeUserSession(Long userId) {
        userSessions.remove(userId);
        log.info("用户 {} WebSocket会话已断开", userId);
    }

    @SuppressWarnings("null")
    public void notifyUser(Long userId, String type, Object data) {
        WebSocketSession session = userSessions.get(userId);
        if (session != null && session.isOpen()) {
            try {
                Map<String, Object> message = Map.of(
                        "type", type,
                        "data", data != null ? data : Map.of(),
                        "timestamp", System.currentTimeMillis()
                );

                String json = objectMapper.writeValueAsString(message);
                
                // 解决并发发送导致的 IllegalStateException
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(json));
                    }
                }

            } catch (Exception e) {
                log.error("发送WebSocket消息失败 - 用户: {}", userId, e);
            }
        }
    }
}