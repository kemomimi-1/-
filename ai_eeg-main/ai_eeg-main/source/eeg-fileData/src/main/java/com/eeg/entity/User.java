//  用户实体类
package com.eeg.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "users")
@NoArgsConstructor
@AllArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 20)
    private String username;

    @Column(nullable = false)
    private String password; // 加密存储

    @Column(name = "raw_port")
    private Integer rawPort;

    @Column(name = "filt_port")
    private Integer filtPort;

    @Column(name = "band_port")
    private Integer bandPort;

    @Column(name = "is_active")
    private Boolean isActive = false;

    @Column(name = "last_connection")
    private LocalDateTime lastConnection;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}