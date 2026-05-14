package io.github.seoleeder.owls_pick.repository.custom;


import io.github.seoleeder.owls_pick.entity.game.StoreDetail;
import io.github.seoleeder.owls_pick.entity.game.StoreDetail.StoreName;

import java.util.List;
import java.util.Set;

public interface StoreDetailRepositoryCustom {
    // 특정 StoreName을 가진 모든 게임 ID 조회
    Set<String> findAllAppIdsByStore(StoreName storeName);

    // 리뷰 데이터가 존재하지 않는 모든 스팀 게임 ID 조회
    List<StoreDetail> findGamesWithNoReviews(StoreName storeName, int limit);

    //리뷰 데이터 업데이트가 필요한 모든 스팀 게임 ID 조회
    List<StoreDetail> findGamesNeedingReviewUpdate(StoreName storeName, int limit);

    // 스팀 AppID로 게임 한번에 조회
    List<StoreDetail> findByStoreNameAndStoreAppIdIn(StoreName storeName, List<String> appIds);

    // ITAD ID가 없는 게임의 스팀 App ID 조회
    List<StoreDetail> findDetailsWithMissingItadId(StoreName storeName, int limit);

    // 특정 상점(StoreName)의 게임 가격 정보 목록 조회
    List<StoreDetail> findDetailsByStoreAndGameIds(StoreName storeName, List<Long> gameIds);

    // 게임 테이블에서 출시일 정보가 존재하면서 ITAD ID가 없는 게임 조회
    List<StoreDetail> findValidGamesMissingItadId(StoreName storeName, Long lastId, int limit);

}
