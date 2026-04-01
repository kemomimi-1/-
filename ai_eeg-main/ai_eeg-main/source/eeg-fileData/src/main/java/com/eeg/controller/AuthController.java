//  认证控制器
package com.eeg.controller;

import com.eeg.entity.User;
import com.eeg.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        try {
            // 验证用户名格式：6-20位字母数字
            if (!request.getUsername().matches("^[a-zA-Z0-9]{6,20}$")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "用户名必须是6-20位字母和数字组合"));
            }

            // 验证密码格式：8-30位，包含字母、数字
            if (!request.getPassword().matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d@$!%*?&]{8,30}$")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "密码必须8-30位，包含至少一个字母和一个数字"));
            }

            User user = userService.register(request.getUsername(), request.getPassword());

            return ResponseEntity.ok(Map.of(
                    "message", "注册成功",
                    "userId", user.getId(),
                    "username", user.getUsername()
            ));

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpSession session) {
        try {
            User user = userService.login(request.getUsername(), request.getPassword());

            // 设置会话
            session.setAttribute("userId", user.getId());
            session.setAttribute("username", user.getUsername());

            return ResponseEntity.ok(Map.of(
                    "message", "登录成功",
                    "userId", user.getId(),
                    "username", user.getUsername()
            ));

        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpSession session) {
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "已退出登录"));
    }

    @GetMapping("/status")
    public ResponseEntity<?> getAuthStatus(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        String username = (String) session.getAttribute("username");

        if (userId != null) {
            return ResponseEntity.ok(Map.of(
                    "authenticated", true,
                    "userId", userId,
                    "username", username
            ));
        } else {
            return ResponseEntity.ok(Map.of("authenticated", false));
        }
    }

    @Data
    public static class RegisterRequest {
        private String username;
        private String password;
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}