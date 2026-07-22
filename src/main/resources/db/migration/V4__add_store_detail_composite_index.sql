-- [store_detail] ITAD 가격 동기화 IN 쿼리 최적화를 위한 복합 인덱스 생성
CREATE INDEX IF NOT EXISTS idx_store_detail_game_store ON store_detail (game_id, store_name);