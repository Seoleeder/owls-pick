package io.github.seoleeder.owls_pick.entity.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "chat_session")
public class ChatSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_chat_session_user_id"))
    private User user;

    //Gen-API로 30자 이내로 요약
    @Column(length = 30)
    private String title;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist // 저장 전 실행
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate // 수정 전 실행
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 세션 제목 수동 변경 로직
     */
    public void updateTitle(String newTitle) {
        this.title = newTitle;
    }
}
