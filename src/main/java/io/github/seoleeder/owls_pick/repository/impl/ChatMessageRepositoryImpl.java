package io.github.seoleeder.owls_pick.repository.impl;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.github.seoleeder.owls_pick.dto.response.chat.ChatMessageResponse;
import io.github.seoleeder.owls_pick.entity.user.ChatMessage;
import io.github.seoleeder.owls_pick.repository.custom.ChatMessageRepositoryCustom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

import static io.github.seoleeder.owls_pick.entity.user.QChatMessage.chatMessage;
import static io.github.seoleeder.owls_pick.entity.user.QChatSession.chatSession;

@Repository
@RequiredArgsConstructor
public class ChatMessageRepositoryImpl implements ChatMessageRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    /**
     * 특정 세션에서의 최근 대화 내역을 지정한 개수만큼 최신순으로 조회
     * */
    @Override
    public List<ChatMessage> findRecentMessages(Long sessionId, int limit) {
        return queryFactory.selectFrom(chatMessage)
                .where(chatMessage.chatSession.id.eq(sessionId))
                .orderBy(chatMessage.createdAt.desc()) // 최신순 정렬
                .limit(limit)                          // 동적 Limit 적용
                .fetch();
    }

    /**
     * 사용자 ID와 세션 ID를 기반으로 채팅 메시지 목록을 오래된 순으로 조회
     */
    @Override
    public List<ChatMessageResponse> findMessagesBySessionIdAndUserId(Long sessionId, Long userId) {
        return queryFactory
                .select(Projections.constructor(ChatMessageResponse.class,
                        chatMessage.id,
                        chatMessage.chatRole.stringValue(),
                        chatMessage.content,
                        chatMessage.createdAt
                ))
                .from(chatMessage)
                .join(chatMessage.chatSession, chatSession)
                .where(
                        chatSession.id.eq(sessionId),
                        chatSession.user.id.eq(userId)
                )
                .orderBy(chatMessage.createdAt.asc())
                .fetch();
    }
}
