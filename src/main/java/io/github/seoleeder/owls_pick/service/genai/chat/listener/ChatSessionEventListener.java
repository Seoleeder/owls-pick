package io.github.seoleeder.owls_pick.service.genai.chat.listener;

import io.github.seoleeder.owls_pick.dto.request.chat.TitleGenerationRequest;
import io.github.seoleeder.owls_pick.dto.response.chat.TitleGenerationResponse;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.repository.ChatSessionRepository;
import io.github.seoleeder.owls_pick.service.genai.chat.event.SessionTitleGenerateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class ChatSessionEventListener {

    private final ChatSessionRepository chatSessionRepository;
    private final RestClient restClient;
    private final GenaiProperties props;
    private static final int MAX_TITLE_LENGTH = 30; // 세션 타이틀 최대 길이

    public ChatSessionEventListener(
            ChatSessionRepository chatSessionRepository,
            @Qualifier("chatRestClient") RestClient restClient,
            GenaiProperties props) {
        this.chatSessionRepository = chatSessionRepository;
        this.restClient = restClient;
        this.props = props;
    }

    /**
     * 최초 발화 메시지 기반 세션 타이틀 비동기 생성 및 DB 반영 이벤트 핸들러
     * */
    @Async
    @EventListener
    @Transactional
    public void handleSessionTitleGeneration(SessionTitleGenerateEvent event) {
        log.info("[Async - ChatService] Starting background title generation for Session ID: {}", event.sessionId());

        try {
            // FastAPI 타이틀 요약 API 호출
            TitleGenerationResponse response = restClient.post()
                    .uri(props.fastapiUrl() + "/api/genai/chat/title/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TitleGenerationRequest(event.userMessage()))
                    .retrieve()
                    .body(TitleGenerationResponse.class);

            // API 응답 데이터 유효성 검증
            if (response != null && response.title() != null && !response.title().isBlank()) {
                // 최대 제한 길이(30자) 초과 시 문자열 앞부분
                String generatedTitle = response.title().length() > MAX_TITLE_LENGTH
                        ? response.title().substring(0, MAX_TITLE_LENGTH - 3) + "..."
                        : response.title();

                //세션 타이틀 업데이트
                chatSessionRepository.findById(event.sessionId())
                        .ifPresent(session -> session.updateTitle(generatedTitle));

                log.info("[Async - ChatService] Session title updated successfully. Session ID: {}", event.sessionId());
            }
        } catch (RestClientException e) {
            // 통신 실패 시 기존 임시 타이틀 유지
            log.warn("[Async - ChatService] Failed to generate title from FastAPI. Keeping fallback title. Error: {}", e.getMessage());
        }
    }
}
