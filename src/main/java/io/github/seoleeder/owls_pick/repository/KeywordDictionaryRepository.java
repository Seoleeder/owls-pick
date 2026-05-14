package io.github.seoleeder.owls_pick.repository;

import io.github.seoleeder.owls_pick.entity.game.KeywordDictionary;
import io.github.seoleeder.owls_pick.repository.custom.KeywordDictionaryRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KeywordDictionaryRepository extends JpaRepository<KeywordDictionary, Long>, KeywordDictionaryRepositoryCustom {
}
