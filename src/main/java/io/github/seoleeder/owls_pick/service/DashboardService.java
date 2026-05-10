package io.github.seoleeder.owls_pick.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.seoleeder.owls_pick.dto.response.DashboardResponse;
import io.github.seoleeder.owls_pick.entity.game.Dashboard;
import io.github.seoleeder.owls_pick.entity.game.Dashboard.CurationType;
import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.global.util.IgdbImageUrlProvider;
import io.github.seoleeder.owls_pick.repository.DashboardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DashboardRepository dashboardRepository;
    private final ObjectMapper objectMapper;
    private final GamePriceService gamePriceService;

    private final IgdbImageUrlProvider imageUrlProvider;

    // 가격 변동 반영을 위해 30분마다 캐시 만료 (랭킹은 유지되더라도 가격은 갱신됨)
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /**
     * 메인 대시보드 페이지 & 큐레이션 타입별 차트 페이지 조회 메서드
     * @param type 큐레이션 타입
     * @param targetDate 클라이언트가 요청한 수집 시각 (null일 경우 최신 캐시 데이터 반환)
     * @param limit 반환 게임 수
     */
    public DashboardResponse getDashboard(CurationType type, LocalDateTime targetDate, int limit) {

        // 과거 시점의 차트 요청시 DB에서 직접 조회
        if (targetDate != null) {

            // 요청된 시각과 오차가 가장 적은 실제 수집 시각 탐색
            LocalDateTime exactDate = dashboardRepository.findClosestReferenceAt(type, targetDate);

            if (exactDate == null) {
                log.warn("[Dashboard] No dashboard data found near requested date. Date: {}, Type: {}", targetDate, type);
                return new DashboardResponse(type.name(), targetDate, null, null, Collections.emptyList());
            }

            log.debug("[Dashboard] Fetched historical dashboard data from DB. Type: {}, Requested: {}, Exact: {}", type, targetDate, exactDate);

            // 보정된 정확한 시각으로 데이터 조회
            return fetchFromDb(type, exactDate, limit);
        }

        // 최신 차트 요청시 Redis 캐시를 우선적으로 조회
        String key = getCacheKey(type);
        try {
            Object cached = redisTemplate.opsForValue().get(key);

            if (cached != null) {
                log.debug("[Dashboard] Cache hit. Type: {}", type);
                // Redis에 저장된 객체를 DTO로 안전하게 역직렬화
                return objectMapper.convertValue(cached, DashboardResponse.class);
            }
        } catch (Exception e) {
            log.error("[Dashboard] Failed to fetch cache from Redis. Type: {}", type, e);
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }


        // 캐시 미스 시 DB의 최신 데이터를 조회하고 Redis에 업데이트 후 반환
        log.debug("[Dashboard] Cache miss. Fetching from DB and updating cache. Type: {}", type);
        return refreshCache(type);
    }

    /**
     * DB에 저장된 가장 최근의 대시보드 데이터를 기반으로 Redis 캐시 최신화
     * @param type 갱신할 큐레이션 타입
     */
    public DashboardResponse refreshCache(CurationType type) {
        log.debug("[Dashboard] Refreshing cache for type: {}", type);

        // 해당 큐레이션 타입의 가장 최근 수집 시각 조회
        LocalDateTime latestDate = dashboardRepository.findLatestReferenceAt(type);

        if (latestDate == null) {
            log.warn("[Dashboard] Cannot refresh cache. No latest reference date found for type: {}", type);
            return new DashboardResponse(type.name(), null, null, null, Collections.emptyList());
        }

        // 최신 수집 시각의 데이터 조회
        DashboardResponse response = fetchFromDb(type, latestDate, 100);

        // 완성된 대시보드 응답을 Redis에 저장 (TTL 30분)
        try {
            redisTemplate.opsForValue().set(getCacheKey(type), response, CACHE_TTL);
            log.debug("[Dashboard] Redis cache updated successfully. Type: {}", type);
        } catch (Exception e) {
            log.error("[Dashboard] Failed to update Redis cache. Type: {}", type, e);
        }

        return response;

    }

    /**
     * 특정 기준 시각에 대한 대시보드 차트, 인접 일자, 게임별 가격 정보를 결합하여 반환
     * @param type 큐레이션 타입
     * @param targetDate 데이터를 조회할 수집 기준 시각
     * @param limit 반환 게임 수
     */
    private DashboardResponse fetchFromDb(CurationType type, LocalDateTime targetDate, int limit) {
        // 기준 시각에 해당하는 스팀 대시보드 데이터 조회
        List<Dashboard> dashboards = dashboardRepository.findGamesByCurationAndDate(type, targetDate, limit);

        if (dashboards.isEmpty()) {
            return new DashboardResponse(type.name(), targetDate, null, null, Collections.emptyList());
        }

        // 수집 기준 시각을 기준으로 이전/다음 수집 시각 조회
        LocalDateTime prevDate = dashboardRepository.findAdjacentDate(type, targetDate, true);
        LocalDateTime nextDate = dashboardRepository.findAdjacentDate(type, targetDate, false);

        // 조회된 게임들의 ID 추출
        List<Long> gameIds = dashboards.stream().map(d -> d.getGame().getId()).toList();

        // 추출된 게임들의 현재 최저가 데이터 매핑
        Map<Long, StoreDetail> lowestPriceMap = gamePriceService.getLowestPriceMap(gameIds);

        // 대시보드 DTO 변환 및 이미지 URL 생성
        List<DashboardResponse.DashboardGameDto> gameDtos = dashboards.stream()
                .map(d -> {
                    Game game = d.getGame();
                    StoreDetail price = lowestPriceMap.get(game.getId());

                    DashboardResponse.DashboardGameDto dashboardGameDto = new DashboardResponse.DashboardGameDto(
                            game.getId(),
                            d.getRank(),
                            game.getTitle(),
                            imageUrlProvider.generateImageUrl(game.getCoverId()),
                            (price != null && price.getOriginalPrice() != null) ? price.getOriginalPrice() : 0,
                            (price != null && price.getDiscountPrice() != null) ? price.getDiscountPrice() : 0,
                            (price != null && price.getDiscountRate() != null) ? price.getDiscountRate() : 0
                    );
                    return dashboardGameDto;
                })
                .toList();

        return new DashboardResponse(type.name(), targetDate, prevDate, nextDate, gameDtos);
    }

    /** Redis에 저장할 대시보드 키를 생성합니다. */
    private String getCacheKey(CurationType type) {
        return "dashboard:latest:" + type.name();
    }
}