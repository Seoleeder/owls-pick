package io.github.seoleeder.owls_pick.service;

import io.github.seoleeder.owls_pick.entity.game.Game;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.repository.StoreDetailRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class GamePriceServiceTest {

    @InjectMocks
    private GamePriceService gamePriceService;

    @Mock
    private StoreDetailRepository storeDetailRepository;

    @Test
    @DisplayName("다중 스토어 입점 게임의 최저가 산출 로직 검증")
    void getLowestPriceMap_MultipleStores_SelectLowestPrice() {
        // [Given] 동일한 게임에 대해 가격이 다른 다중 스토어 데이터 세팅
        Game targetGame = Game.builder().title("Target Game").build();

        StoreDetail expensiveStore = StoreDetail.builder()
                .game(targetGame)
                .storeName(StoreDetail.StoreName.STEAM)
                .discountPrice(50000)
                .build();

        StoreDetail cheapStore = StoreDetail.builder()
                .game(targetGame)
                .storeName(StoreDetail.StoreName.EPIC_GAMES_STORE)
                .discountPrice(30000) // 여기가 최저가
                .build();

        when(storeDetailRepository.findAllByGameIdIn(anyList()))
                .thenReturn(List.of(expensiveStore, cheapStore));

        // [When] 최저가 산출 로직 실행
        Map<Long, StoreDetail> result = gamePriceService.getLowestPriceMap(List.of(1L));

        // [Then] 여러 스토어 중 최저가 데이터(30000원) 채택 확인
        assertThat(result).isNotEmpty();
        StoreDetail lowestPriceDetail = result.values().iterator().next();
        assertThat(lowestPriceDetail.getDiscountPrice()).isEqualTo(30000);
        assertThat(lowestPriceDetail.getStoreName()).isEqualTo(StoreDetail.StoreName.EPIC_GAMES_STORE);
    }

    @Test
    @DisplayName("할인가 결측(Null) 스토어 경합 시 예외 방어 및 정상가 채택 검증")
    void getLowestPriceMap_NullPriceHandling_Success() {
        // [Given] 할인가가 없는(Null) 스토어와 정상 할인가를 가진 스토어 데이터 세팅
        Game targetGame = Game.builder().title("Null Test Game").build();

        StoreDetail nullPriceStore = StoreDetail.builder()
                .game(targetGame)
                .storeName(StoreDetail.StoreName.STEAM)
                .discountPrice(null)
                .build();

        StoreDetail validPriceStore = StoreDetail.builder()
                .game(targetGame)
                .storeName(StoreDetail.StoreName.EPIC_GAMES_STORE)
                .discountPrice(10000)
                .build();

        when(storeDetailRepository.findAllByGameIdIn(anyList()))
                .thenReturn(List.of(nullPriceStore, validPriceStore));

        // [When] 최저가 산출 로직 실행
        Map<Long, StoreDetail> result = gamePriceService.getLowestPriceMap(List.of(2L));

        // [Then] NullPointerException 없이 정상 가격 데이터 채택 확인
        assertThat(result).isNotEmpty();
        StoreDetail lowestPriceDetail = result.values().iterator().next();
        assertThat(lowestPriceDetail.getDiscountPrice()).isEqualTo(10000);
    }

    @Test
    @DisplayName("DB 조회 예외 발생 시 애플리케이션 중단 없는 빈 데이터 반환 검증")
    void getLowestPriceMap_ExceptionThrown_ReturnEmptyMap() {
        // [Given] 리포지토리 호출 시 DB 통신 에러 발생 상황 세팅
        when(storeDetailRepository.findAllByGameIdIn(anyList()))
                .thenThrow(new RuntimeException("DB Connection Timeout"));

        // [When] 최저가 산출 로직 실행
        Map<Long, StoreDetail> result = gamePriceService.getLowestPriceMap(List.of(1L, 2L));

        // [Then] 예외 발생 시 메인 프로세스 중단 없이 빈 맵 반환 확인
        assertThat(result).isEmpty();
    }
}