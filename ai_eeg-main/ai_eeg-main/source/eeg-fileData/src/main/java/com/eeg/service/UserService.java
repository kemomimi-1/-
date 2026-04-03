// 用户服务
package com.eeg.service;

import com.eeg.entity.User;
import com.eeg.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Transactional
    public User register(String username, String password) {
        // 检查用户名是否已存在
        if (userRepository.findByUsername(username).isPresent()) {
            throw new RuntimeException("用户名已存在");
        }

        // 创建新用户
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setIsActive(false);

        User savedUser = userRepository.save(user);
        log.info("新用户注册成功: {}", username);
        return savedUser;
    }

    public User login(String username, String password) {
        Optional<User> userOpt = userRepository.findByUsername(username);

        if (userOpt.isEmpty()) {
            throw new RuntimeException("用户不存在");
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("密码错误");
        }

        // 更新最后连接时间
        user.setLastConnection(LocalDateTime.now());
        userRepository.save(user);

        log.info("用户登录成功: {}", username);
        return user;
    }

    @SuppressWarnings("null")
    @Transactional
    public void updateUserPorts(Long userId, int rawPort, int filtPort, int bandPort) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        user.setRawPort(rawPort);
        user.setFiltPort(filtPort);
        user.setBandPort(bandPort);
        user.setIsActive(true);

        userRepository.save(user);
        log.info("用户 {} 端口信息已更新: Raw={}, Filt={}, Band={}",
                userId, rawPort, filtPort, bandPort);
    }

    @SuppressWarnings("null")
    @Transactional
    public void setUserInactive(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));

        user.setIsActive(false);
        userRepository.save(user);
        log.info("用户 {} 已设置为非活跃状态", userId);
    }

    @SuppressWarnings("null")
    public User findById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
    }
}