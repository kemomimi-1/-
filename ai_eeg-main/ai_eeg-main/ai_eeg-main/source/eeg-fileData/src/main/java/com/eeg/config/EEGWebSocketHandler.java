
package com.eeg.config;

import com.eeg.service.WebSocketNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class EEGWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketNotificationService notificationService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 从查询参数获取userId
        URI uri = session.getUri();
        if (uri != null && uri.getQuery() != null) {
            Map<String, String> params = parseQueryString(uri.getQuery());
            String userIdStr = params.get("userId");
            if (userIdStr != null) {
                try {
                    Long userId = Long.parseLong(userIdStr);
                    notificationService.addUserSession(userId, session);
                    log.info("用户 {} WebSocket连接建立", userId);
                } catch (NumberFormatException e) {
                    log.warn("无效的userId参数: {}", userIdStr);
                    session.close();
                }
            } else {
                log.warn("WebSocket连接未提供userId参数");
                session.close();
            }
        } else {
            log.warn("WebSocket连接URI无效");
            session.close();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        URI uri = session.getUri();
        if (uri != null && uri.getQuery() != null) {
            Map<String, String> params = parseQueryString(uri.getQuery());
            String userIdStr = params.get("userId");
            if (userIdStr != null) {
                try {
                    Long userId = Long.parseLong(userIdStr);
                    notificationService.removeUserSession(userId);
                    log.info("用户 {} WebSocket连接关闭", userId);
                } catch (NumberFormatException e) {
                    log.warn("无效的userId参数: {}", userIdStr);
                }
            }
        }
    }

    /**
     * 解析查询字符串参数
     */
    private Map<String, String> parseQueryString(String query) {
        return Arrays.stream(query.split("&"))
                .filter(param -> param.contains("="))
                .map(param -> param.split("=", 2))
                .collect(Collectors.toMap(
                        arr -> arr[0],
                        arr -> arr.length > 1 ? arr[1] : "",
                        (existing, replacement) -> existing
                ));
    }
}