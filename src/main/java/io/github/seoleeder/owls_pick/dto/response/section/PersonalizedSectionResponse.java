package io.github.seoleeder.owls_pick.dto.response.section;

import io.github.seoleeder.owls_pick.dto.response.GameResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.data.domain.Page;

@Schema(description = "개인화된 맞춤 게임 추천 섹션 응답 DTO")
public record PersonalizedSectionResponse (

    @Schema(description = "섹션 타이틀에 들어갈 동적 태그(장르, 테마)", example = "판타지 액션")
    String titleKeyword,

    @Schema(description = "해당 태그로 조회 및 필터링된 게임들의 페이징 결과 목록")
    Page<GameResponse> games
){
}
