package io.github.seoleeder.owls_pick.entity.genai;

import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiFailReason;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiPipelineType;
import jakarta.persistence.*;
import lombok.*;
import org.slf4j.MDC;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "genai_failed_task")
public class GenaiFailedTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private GenaiPipelineType pipelineType;

    @Column(nullable = false)
    private Long targetId;  // 실패한 작업 엔티티의 식별자

    @Column(length = 50)
    private String traceId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isHandled = false; // 최종 조치 여부

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private GenaiFailReason failReason;

    @Column
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;

        if (this.traceId == null) {
            // 현재 스레드의 로깅 컨텍스트에서 Trace ID를 자동 추출
            this.traceId = MDC.get("traceId");
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void markAsHandled() {
        this.isHandled = true;
    }
}