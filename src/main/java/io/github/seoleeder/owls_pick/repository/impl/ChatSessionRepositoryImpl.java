package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.dto.response.chat.ChatSessionListResponse;
import io.github.seoleeder.owls_pick.repository.custom.ChatSessionRepositoryCustom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static io.github.seoleeder.owls_pick.entity.user.QChatSession.chatSession;


@Repository
@RequiredArgsConstructor
public class ChatSessionRepositoryImpl implements ChatSessionRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    /**
     * 특정 유저의 채팅 세션 목록 최신순 조회
     */
    @Override
    public List<ChatSessionListResponse> findSessionsByUserId(Long userId) {
        return queryFactory
                .select(Projections.constructor(ChatSessionListResponse.class,
                        chatSession.id,
                        chatSession.title,
                        chatSession.updatedAt
                ))
                .from(chatSession)
                .where(chatSession.user.id.eq(userId))
                .orderBy(chatSession.updatedAt.desc())
                .fetch();
    }
}
