package io.github.seoleeder.owls_pick.entity.game;

import io.github.seoleeder.owls_pick.entity.game.enums.GameModeType;
import io.github.seoleeder.owls_pick.entity.game.enums.PerspectiveType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "game")
public class Game {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private Long igdbId;

    @Column
    private String itadId;

    @Column(nullable = false)
    private String title;

    @Column
    private String titleLocalization;

    @Column(length = 30)
    private String type;

    @Column
    private String releaseStatus;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Builder.Default
    @Column(columnDefinition = "text[]")
    private List<String> platform = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "description_ko", columnDefinition = "TEXT")
    private String descriptionKo;

    @Column(columnDefinition = "TEXT")
    private String storyline;

    @Column(name = "storyline_ko", columnDefinition = "TEXT")
    private String storylineKo;

    @Column
    private LocalDate firstRelease;

    @Column(length = 30)
    private String ratingKr;

    @Column(length = 30)
    private String ratingEsrb;

    @Column (nullable = false)
    @Builder.Default
    private Boolean isAdult = false;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Builder.Default
    @Column(columnDefinition = "text[]")
    private List<GameModeType> mode = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Builder.Default
    @Column(columnDefinition = "text[]")
    private List<PerspectiveType> perspective = new ArrayList<>();

    @Column(length = 30)
    private String coverId;

    @Column
    private int hypes;

    @Column
    private LocalDateTime igdbUpdatedAt;

    @Column
    private LocalDateTime createdAt;

    @Column
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


    public void connectToIgdb(Long newIgdbId) {
        if (this.igdbId != null) {
            return;
        }
        this.igdbId = newIgdbId;
    }

    public void updateItadId(String itadId){
        if (this.itadId != null) {
            return;
        }
        this.itadId = itadId;
    }

    //IGDB Summary Date Update
    public void updateFromSummary(
            String titleLocalization,
            String type,
            String releaseStatus,
            List<String> platform,
            String description,
            String storyline,
            LocalDate firstRelease,
            int hypes,
            String coverId,
            String ratingKr,
            String ratingEsrb,
            List<GameModeType> mode,
            List<PerspectiveType> perspective,
            LocalDateTime igdbUpdatedAt
    ) {
        if(this.igdbId == null) {
            this.igdbId = igdbId;
        }

        if (titleLocalization != null && !titleLocalization.isBlank()) {
            this.titleLocalization = titleLocalization;
        }

        this.description = description;
        this.storyline = storyline;
        this.type = type;
        this.releaseStatus = releaseStatus;
        this.firstRelease = firstRelease;
        this.igdbUpdatedAt = igdbUpdatedAt;
        this.hypes = hypes;
        this.coverId = coverId;

        // 심의 등급 할당
        this.ratingEsrb = ratingEsrb;
        this.ratingKr = ratingKr;

        // 리스트 교체
        this.platform = platform;
        this.mode = mode;
        this.perspective = perspective;
    }

    /**
     * 게임 설명, 스토리라인 한글화 업데이트
     */
    public void updateLocalization(String descriptionKo, String storylineKo) {
        this.descriptionKo = descriptionKo;
        this.storylineKo = storylineKo;
    }

    /**
     *  심의 등급(국내 및 북미)과 태그 정보를 바탕으로 성인 콘텐츠 여부 판단 및 상태 갱신
     */
    public void evaluateAdultStatus(Tag tag) {
        boolean isAdultRating = false;

        // 심의 등급 판별 (국내 심의 우선)
        if (this.ratingKr != null && !this.ratingKr.isBlank()) {
            // 국내 심의 데이터가 존재하면 그것만으로 판단 (글로벌 심의는 무시)
            isAdultRating = this.ratingKr.contains("18") || this.ratingKr.contains("19");

        } else if (this.ratingEsrb != null && !this.ratingEsrb.isBlank()) {
            // 국내 심의 데이터가 없을 경우에만 글로벌 심의 등급으로 판단
            isAdultRating = this.ratingEsrb.equalsIgnoreCase("M") || this.ratingEsrb.equalsIgnoreCase("AO");
        }
        boolean hasAdultTag = (tag != null && tag.isAdult());

        // 최종 상태 갱신 (청불 등급이거나, 성인 태그가 하나라도 있다면 true)
        this.isAdult = isAdultRating || hasAdultTag;
    }
}
