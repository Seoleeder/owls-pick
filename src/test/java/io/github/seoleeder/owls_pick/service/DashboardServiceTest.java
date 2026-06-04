package io.github.seoleeder.owls_pick.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.seoleeder.owls_pick.dto.response.DashboardResponse;
import io.github.seoleeder.owls_pick.entity.game.Dashboard.CurationType;
import io.github.seoleeder.owls_pick.global.util.IgdbImageUrlProvider;
import io.github.seoleeder.owls_pick.repository.DashboardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DashboardServiceTest {

    @InjectMocks
    private DashboardService dashboardService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private DashboardRepository dashboardRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private GamePriceService gamePriceService;

    @Mock
    private IgdbImageUrlProvider imageUrlProvider;

    @Test
    @DisplayName("Redis 캐시 Hit 시 DB 조회 없이 캐시 데이터 DTO 반환 검증")
    void getDashboard_CacheHit_ReturnCachedData() {
        // [Given] Redis 캐시 연산 객체 및 임의의 캐시 데이터 모킹
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Object cachedData = new Object();
        when(valueOperations.get(anyString())).thenReturn(cachedData);

        // [Given] ObjectMapper 역직렬화 결과 모킹
        DashboardResponse mockResponse = new DashboardResponse(CurationType.WEEKLY_TOP_SELLER.name(), null, null, null, null);
        when(objectMapper.convertValue(cachedData, DashboardResponse.class)).thenReturn(mockResponse);

        // [When] 기준 일자(targetDate)가 null인 최신 대시보드 데이터 요청
        DashboardResponse result = dashboardService.getDashboard(CurationType.WEEKLY_TOP_SELLER, null, 10);

        // [Then] 결과 반환 확인 및 DB 조회 리포지토리 메서드 미호출(0회) 검증
        assertThat(result.curationType()).isEqualTo(CurationType.WEEKLY_TOP_SELLER.name());
        verify(dashboardRepository, never()).findLatestReferenceAt(any());
        verify(dashboardRepository, never()).findGamesByCurationAndDate(any(), any(), anyInt());
    }

    @Test
    @DisplayName("과거 시점 차트 요청 시 캐시 우회 및 DB 직접 조회 검증")
    void getDashboard_WithTargetDate_FetchFromDb() {
        // [Given] 과거 타겟 시각 및 DB 내 인접 기준 시각 세팅
        LocalDateTime targetDate = LocalDateTime.now().minusDays(1);
        LocalDateTime exactDate = targetDate.minusMinutes(5);

        // [Given] 인접 시각 탐색 리포지토리 동작 모킹
        when(dashboardRepository.findClosestReferenceAt(CurationType.WEEKLY_TOP_SELLER, targetDate)).thenReturn(exactDate);

        // [When] 특정 과거 시각이 포함된 대시보드 데이터 요청
        dashboardService.getDashboard(CurationType.WEEKLY_TOP_SELLER, targetDate, 10);

        // [Then] Redis 캐시 접근 메서드 미호출(0회) 검증
        verify(redisTemplate, never()).opsForValue();

        // [Then] 보정된 인접 시각(exactDate) 기반의 DB 조회 메서드 호출 검증
        verify(dashboardRepository, times(1)).findGamesByCurationAndDate(eq(CurationType.WEEKLY_TOP_SELLER), eq(exactDate), anyInt());
    }
}