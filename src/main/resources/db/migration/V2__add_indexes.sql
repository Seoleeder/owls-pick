-- ===========================================================================
-- 1. 게임(Game) 도메인
-- ===========================================================================
-- [game] 게임 기본 정보
create index if not exists idx_game_title on game (title);

-- [store_detail] 상점 할인 정보
create index if not exists idx_store_detail_discount_rate on store_detail (discount_rate);

-- [tag] 게임 메타 데이터 배열
create index if not exists idx_tag_genres on tag using gin (genres);
create index if not exists idx_tag_themes on tag using gin (themes);
create index if not exists idx_tag_keywords on tag using gin (keywords);

-- [language_support] 언어 지원 정보
create index if not exists idx_language_support_game_id on language_support (game_id);

-- [dashboard] 랭킹 및 집계 데이터
create unique index if not exists uk_dashboard_type_date_game on dashboard (curation_type, reference_at, game_id);
create index if not exists idx_dashboard_game_id on dashboard (game_id);
create index if not exists idx_dashboard_curation_type on dashboard (curation_type);

-- [screenshot] 스크린샷 이미지
create index if not exists idx_screenshot_game_id on screenshot (game_id);

-- [review] 리뷰 정보
create index if not exists idx_review_game_id on review (game_id);
create index if not exists idx_review_created_at on review (created_at);

-- [vector_embedding] 게임 벡터 데이터 (초고속 코사인 유사도 검색 지원)
create index if not exists idx_vector_embedding_hnsw on vector_embedding using hnsw (embedding vector_cosine_ops);

-- ===========================================================================
-- 2. 사용자(User) 도메인
-- ===========================================================================
-- [users] 사용자 기본 정보
create index if not exists idx_users_preferred_stores on users using gin (preferred_stores);
create index if not exists idx_users_preferred_tags on users using gin (preferred_tags);

-- [wishlist] 찜 목록 (복합키 역방향 검색 지원)
create index if not exists idx_wishlist_user_id on wishlist (user_id);

-- [fcm_token] 푸시 알림 토큰
create index if not exists idx_fcm_token_user_id on fcm_token (user_id);

-- [notification_history] 푸시 알림 발송 이력
create index if not exists idx_notification_history_user_id on notification_history (user_id);
create index if not exists idx_notification_history_game_id on notification_history (game_id);

-- [chat_session] 채팅 세션
create index if not exists idx_chat_session_user_id on chat_session (user_id);

-- [chat_message] 채팅 메시지 기록
create index if not exists idx_chat_message_session_id on chat_message (session_id);

