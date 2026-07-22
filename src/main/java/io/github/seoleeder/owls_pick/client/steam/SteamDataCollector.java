package io.github.seoleeder.owls_pick.client.steam;

import io.github.seoleeder.owls_pick.client.steam.dto.Dashboard.*;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewDetailResponse;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewDetailResponse.SteamReviewDetail;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewResponse;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewStatsResponse;
import io.github.seoleeder.owls_pick.client.steam.dto.Review.SteamReviewStatsResponse.SteamReviewStats;
import io.github.seoleeder.owls_pick.client.steam.dto.SteamAppListResponse;
import io.github.seoleeder.owls_pick.client.steam.policy.SteamReviewCollectionPolicy;
import io.github.seoleeder.owls_pick.global.config.properties.SteamProperties;
import io.github.seoleeder.owls_pick.global.util.TimestampUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class SteamDataCollector {
    private final SteamClient steamClient;
    private final SteamApiCaller steamApiCaller; // 스팀 리뷰 전용 Caller
    private final SteamProperties steamProps;

    // 최소 리뷰 수
    private static final int MIN_TOTAL_REVIEWS = 10;

    private static final LocalDateTime MIN_COLLECTION_DATE = LocalDateTime.of(2022, 1, 1, 0, 0);

    /**
     * 스팀 등록 게임 10k 메타데이터 수집 (ID, Title)
     */
    public SteamAppListResponse collectAppList(Long lastAppId) {
        return steamClient.getAppList(lastAppId, 10000);
    }

    /**
     * 전략적 리뷰 수집
     * 리뷰 통계 조회 -> p/n 별로 목표 설정 -> 배치 수집
     */
    public SteamReviewResponse collectRefinedReviews(Long appId) {

        // Caller를 통한 안전한 리뷰 통계 조회
        SteamReviewStatsResponse statsResponse = steamApiCaller.getReviewStatSafe(appId);

        // null 체크
        if (statsResponse == null || statsResponse.querySummary() == null) {
            return null;
        }

        SteamReviewStats summary = statsResponse.querySummary();
        int totalReviews = summary.totalReview();

        // 리뷰가 임계보다 적으면 리소스 낭비 방지를 위해 패스
        if (totalReviews < MIN_TOTAL_REVIEWS) {
            return new SteamReviewResponse(summary, Collections.emptyList());
        }

        // 총 리뷰 수에 기반한 수집 정책 발급
        SteamReviewCollectionPolicy policy = SteamReviewCollectionPolicy.of(totalReviews);

        // 긍정/부정 비율에 따른 할당량 계산
        double posRatio = (double) summary.totalPositive() / totalReviews;
        int posQuota = (int) (policy.targetQuota() * posRatio);
        int negQuota = policy.targetQuota() - posQuota;

        // 리뷰 상세 데이터 수집
        List<SteamReviewDetail> collectedReviews = new ArrayList<>();
        collectedReviews.addAll(collectReviewByQuota(appId, "positive", posQuota, policy));
        collectedReviews.addAll(collectReviewByQuota(appId, "negative", negQuota, policy));

        return new SteamReviewResponse(summary,collectedReviews);
    }

    /**
     * 특정 타입(긍정/부정) 리뷰에 대해 목표치만큼 수집
     */
    public List<SteamReviewDetail> collectReviewByQuota(Long appId, String reviewType, int quota, SteamReviewCollectionPolicy policy) {
        List<SteamReviewDetail> collected = new ArrayList<>();
        Set<Long> processedIds = new HashSet<>(); // 커서 리셋 시 중복 수집을 방지하기 위한 Set
        String cursor = "*"; // 커서 초기화


        int retryCount = 0; // 누적 재시도 횟수

        final int maxRetry = steamProps.review().maxRetry();
        final long backoffBaseMs = steamProps.review().backoffBaseMs();

        while (collected.size() < quota) {

            try{
                // 배치 단위 수집 요청 (num_per_page = 100)
                // Caller를 통한 안전한 상세 리뷰 조회 (루프마다 RateLimiter 제어)
                SteamReviewDetailResponse response = steamApiCaller.getReviewDetailSafe(appId, cursor, reviewType);

                if (response == null || response.reviews() == null || response.reviews().isEmpty()) {
                    break;
                }

                retryCount = 0; // 정상 응답 수신 시 재시도 카운트 초기화

                // 정책 기준(유용함 점수, 최소 글자 수)을 만족하는 리뷰 필터링 (이미 수집된 ID는 제외)
                List<SteamReviewDetailResponse.SteamReviewDetail> filtered = response.reviews().stream()
                        .filter(r -> r.votesUp() >= policy.minVotesUp())
                        .filter(r -> r.reviewText() != null && r.reviewText().trim().length() >= policy.minLength())
                        .filter(r -> !processedIds.contains(r.recommendationId()))
                        .toList();


                // 할당량(Quota)에 맞춰 필요한 만큼만 추출하여 추가
                int needed = quota - collected.size();
                List<SteamReviewDetailResponse.SteamReviewDetail> toAdd =
                        filtered.size() > needed ? filtered.subList(0, needed) : filtered;

                collected.addAll(toAdd);

                // 중복 방지를 위해 방금 수집된 리뷰들의 recommendationId 기록
                toAdd.forEach(r -> processedIds.add(r.recommendationId()));

                // 다음 페이지 탐색을 위한 커서 갱신 및 정체 확인
                String nextCursor = response.cursor();
                if (nextCursor == null || nextCursor.equals(cursor)) break;

                cursor = nextCursor;

            }catch (Exception e) {

                // 복구 불가능한 예외 처리: 400, 404 등 논리적 오류 시 수집 즉시 중단
                if (!isRecoverableException(e)) {
                    log.error("Unrecoverable error for AppID {} (Type: {}). Reason: {}", appId, reviewType, e.getMessage());
                    break;
                }

                // 복구 가능한 예외(429 Rate Limit, Timeout 등) 발생 시 카운트 증가
                retryCount++;

                // 백오프(Backoff) 기반 재시도 로직 실행
                if (retryCount <= maxRetry) {
                    // 재시도 횟수에 비례하여 대기 시간 증가
                    long backoffTime = backoffBaseMs* retryCount;
                    log.warn("API Timeout or 429 detected for AppID {} (Cursor: {}). Retrying in {}ms... ({}/{})",
                            appId, cursor, backoffTime, retryCount, maxRetry);

                    try {
                        Thread.sleep(backoffTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    // 최대 재시도 횟수 초과 시 최종 실패 처리 및 루프 종료
                    log.error("AppID {} ({}) Max retries exceeded. Stopping collection. Reason: {}", appId, reviewType, e.getMessage());
                    break; // 5번 연속 실패 시에만 진짜 포기
                }
            }
        }

        return collected;
    }

    /**
     * 주간 최고 매출 게임 랭크 데이터 수집
     * @param countryCode 집계가 반영되는 국가 코드 (대한민국 = KR)
     * @param pageStart 해당 페이지에서 몇위부터 반환할지 명시
     * @param pageCount 페이지당 가져올 게임의 개수 (매출별로 정렬)
     */
    public SteamDashboardResponse collectWeeklyTopSellers (String countryCode, Long startDate, Integer pageStart, Integer pageCount){
        SteamWeeklyTopSellersResponse response = steamClient.getWeeklyTopSeller(countryCode, startDate, pageStart, pageCount);

        if (response == null || response.response() == null) {
            return null;
        }

        // steam 상의 timestamp를 LocalDateTime으로 변환
        LocalDateTime collectedDate = TimestampUtils.toLocalDateTime(response.response().startDate());

        if (collectedDate.isBefore(MIN_COLLECTION_DATE)){
            return null;
        }

        //WeeklyTopSellerDTO를 Steam 대시보드 DTO와 매핑
        List<SteamDashboardResponse.Rank> ranks = response.response().ranks()
                .stream().map(rank -> new SteamDashboardResponse.Rank(rank.rank(), rank.appid())).toList();

        return new SteamDashboardResponse(collectedDate, ranks);
    }

    /**
     * 연간 스팀 우수작 데이터 수집
     * @param rtimeYear 기준이 되는 timestamp (연도)
     * */
    public SteamDashboardResponse collectYearTopApp(Long rtimeYear){
        SteamYearOrMonthTopAppResponse response = steamClient.getYearTopApp(rtimeYear);
        
        if(response == null || response.response() == null){
            return null;
        }

        // 연간 우수작 리스트가 null이거나 비어있는 경우 스킵
        if (response.response().topApp() == null || response.response().topApp().isEmpty()) {
            log.warn("No top apps data found for year timestamp: {}", rtimeYear);
            return null;
        }

        LocalDateTime collectedYear = TimestampUtils.toLocalDateTime(rtimeYear);
        if(collectedYear.isBefore(MIN_COLLECTION_DATE)){
            return null;
        }

        List<SteamDashboardResponse.Rank> ranks = response.response().topApp().stream()
                .map(top -> new SteamDashboardResponse.Rank(top.rank(), top.appId()))
                .toList();
        
        return new SteamDashboardResponse(collectedYear, ranks);

    }

    /**
     * 월간 스팀 우수작 데이터 수집
     * @param rtimeMonth 기준이 되는 timestamp (월)
     * */
    public SteamDashboardResponse collectMonthTopApp(Long rtimeMonth){
        SteamYearOrMonthTopAppResponse response = steamClient.getMonthTopApp(rtimeMonth);

        if(response == null || response.response() == null){
            return null;
        }

        LocalDateTime collectedMonth = TimestampUtils.toLocalDateTime(rtimeMonth);
        if(collectedMonth.isBefore(MIN_COLLECTION_DATE)){
            return null;
        }

        List<SteamDashboardResponse.Rank> ranks = response.response().topApp().stream()
                .map(top -> new SteamDashboardResponse.Rank(top.rank(), top.appId()))
                .toList();

        return new SteamDashboardResponse(collectedMonth, ranks);

    }

    /**
     * 스팀 게임 최다 동시 접속자수 랭크 데이터 수집 (15분 간격 업데이트)
     * @param countryCode 랭크가 집계되는 국가 코드 (KR)
     * */
    public SteamDashboardResponse collectConcurrentPlayersTopApp(String countryCode) {
        SteamConcurrentPlayersTopAppResponse response = steamClient.getConcurrentPlayersTopApp(countryCode);

        if (response == null || response.response() == null || response.response().ranks() == null) {
            return null;
        }

        LocalDateTime updateAt = TimestampUtils.toLocalDateTime(response.response().updatedAt());

        List<SteamDashboardResponse.Rank> ranks = response.response().ranks().stream()
                .map(r -> new SteamDashboardResponse.Rank(r.rank(), r.appid()))
                .toList();

        return new SteamDashboardResponse(updateAt, ranks);
    }

    /**
     * 스팀 최다 플레이 게임 랭크 데이터 수집 (일일 업데이트)
     * @param countryCode 랭크가 집계되는 국가 코드(KR)
     * */
    public SteamDashboardResponse collectMostPlayedApp(String countryCode) {
        SteamMostPlayedAppResponse response = steamClient.getMostPlayedApp(countryCode);

        if (response == null || response.response().ranks() == null) {
            return null;
        }

        LocalDateTime updateAt = TimestampUtils.toLocalDateTime(response.response().updatedAt());

        List<SteamDashboardResponse.Rank> ranks = response.response().ranks().stream()
                .map(r -> new SteamDashboardResponse.Rank(r.rank(), r.appid()))
                .toList();

        return new SteamDashboardResponse(updateAt, ranks);
    }

    /**
     * 재시도가 필요한 일시적 예외(429, 5xx, Timeout) 여부 판별
     */
    private boolean isRecoverableException(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // steamApiCaller가 사용하는 HTTP 클라이언트(RestTemplate, WebClient 등)의
        // 실제 예외 타입에 맞게 instanceof 검사를 추가하거나 메시지로 판별합니다.
        return msg.contains("429") || msg.contains("500") || msg.contains("502")
                || msg.contains("503") || msg.contains("504") || msg.contains("timeout")
                || e instanceof java.net.SocketTimeoutException;
    }

}
