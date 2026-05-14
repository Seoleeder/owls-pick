package io.github.seoleeder.owls_pick.service.genai.chat;

import io.github.seoleeder.owls_pick.dto.request.chat.*;
import io.github.seoleeder.owls_pick.dto.response.chat.ChatResponse;
import io.github.seoleeder.owls_pick.dto.response.chat.QueryEmbeddingResponse;
import io.github.seoleeder.owls_pick.dto.response.chat.RagGenerationResponse;
import io.github.seoleeder.owls_pick.dto.response.chat.TitleGenerationResponse;
import io.github.seoleeder.owls_pick.entity.game.VectorEmbedding;
import io.github.seoleeder.owls_pick.entity.user.ChatMessage;
import io.github.seoleeder.owls_pick.entity.user.ChatMessage.ChatRole;
import io.github.seoleeder.owls_pick.entity.user.ChatSession;
import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.ChatMessageRepository;
import io.github.seoleeder.owls_pick.repository.ChatSessionRepository;
import io.github.seoleeder.owls_pick.repository.UserRepository;
import io.github.seoleeder.owls_pick.repository.VectorEmbeddingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class ChatService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final VectorEmbeddingRepository vectorEmbeddingRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final RestClient restClient;
    private final GenaiProperties props;

    private static final int MAX_TITLE_LENGTH = 30; // 세션 타이틀 최대 길이

    public ChatService(
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            VectorEmbeddingRepository vectorEmbeddingRepository,
            UserRepository userRepository,
            TransactionTemplate transactionTemplate,
            @Qualifier("chatRestClient") RestClient restClient,
            GenaiProperties props) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.vectorEmbeddingRepository = vectorEmbeddingRepository;
        this.userRepository = userRepository;
        this.transactionTemplate = transactionTemplate;
        this.restClient = restClient;
        this.props = props;
    }

    /**
     * 트랜잭션 내부에서 로드된 데이터(세션, 대화 내역)를 한 번에 외부로 반환하기 위한 레코드
     */
    private record ChatInitData(ChatSession session, List<ChatHistoryDto> history) {}

    /**
     * RAG 기반 실시간 게임 추천 챗봇 파이프라인
     */
    public ChatResponse processRagChat(Long userId, ChatRequest request) {

        // 초기 DB 작업 단일 트랜잭션 처리
        ChatInitData chatInitData = transactionTemplate.execute(status -> {

            // 채팅 세션 및 최근 대화 내역 조회
            ChatSession session = getOrCreateSession(userId, request);
            List<ChatHistoryDto> history = getChatHistory(session.getId(), props.chat().historyLimit());

            // 사용자 메시지 저장
            saveChatMessage(session, ChatRole.USER, request.userMessage());

            return new ChatInitData(session, history);
        });

        if (chatInitData == null) throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);

        ChatSession session = chatInitData.session();
        List<ChatHistoryDto> history = chatInitData.history();

        // 메시지 벡터 임베딩 추출 (사용자 메시지 + 최근 대화 내역)
        float[] queryVector = fetchQueryEmbedding(history, request.userMessage());

        // 벡터 유사도 기반 상위 연관 게임 검색
        List<VectorEmbedding> similarGames = vectorEmbeddingRepository.findTopSimilarGames(queryVector, 5);

        // 연관 게임 조회 실패 시 예외 처리
        if (similarGames.isEmpty()) {
            throw new CustomException(ErrorCode.NOT_FOUND_GAME);
        }


        // Vector DB 검색 결과 상세 추적 (데이터 유실 구간 파악)
        if (log.isDebugEnabled()) {
            log.debug("Retrieved {} similar games from Vector DB.", similarGames.size());
            for (int i = 0; i < similarGames.size(); i++) {
                VectorEmbedding game = similarGames.get(i);
                String textPreview = game.getSourceText().length() > 50
                        ? game.getSourceText().substring(0, 50) + "..."
                        : game.getSourceText();

                log.debug("Rank {}: Game ID = {}, Source Text Preview = {}", (i + 1), game.getGameId(), textPreview);
            }
        }

        // 연관 게임의 원본 메타데이터 추출
        List<String> contexts = similarGames.stream()
                .map(VectorEmbedding::getSourceText)
                .toList();

        // RAG 기반 최종 응답 생성
        String reply = fetchGeneratedChat(history, request.userMessage(), contexts);

        // 응답 메시지 저장 (내부 트랜잭션 적용)
        transactionTemplate.executeWithoutResult(status ->
                saveChatMessage(session, ChatRole.ASSISTANT, reply)
        );

        // 최종 응답 반환
        return new ChatResponse(session.getId(), reply);
    }

    /**
     * 유효한 채팅 세션 반환 또는 신규 세션 생성
     */
    private ChatSession getOrCreateSession(Long userId, ChatRequest request) {
        if (request.sessionId() != null) {
            return chatSessionRepository.findById(request.sessionId())
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_SESSION));
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_USER));


        // 사용자의 첫 발화를 기반으로 세션 타이틀 생성
        String title = fetchGeneratedTitle(request.userMessage());

        // 신규 세션 저장
        return chatSessionRepository.save(ChatSession.builder()
                .user(user)
                .title(title)
                .build());
    }

    /**
     * FastAPI 기반 세션 타이틀 요약 요청 및 반환
     */
    private String fetchGeneratedTitle(String userMessage) {
        try {
            TitleGenerationResponse response = restClient.post()
                    .uri(props.fastapiUrl() + "/api/genai/chat/title/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TitleGenerationRequest(userMessage))
                    .retrieve()
                    .body(TitleGenerationResponse.class);

            if (response != null && response.title() != null && !response.title().isBlank()) {
                // 세션 타이틀 최대 길이 제한 적용
                return response.title().length() > MAX_TITLE_LENGTH
                        ? response.title().substring(0, MAX_TITLE_LENGTH - 3) + "..."
                        : response.title();
            }
        } catch (RestClientException e) {
            log.warn("[ChatService] Failed to generate title from FastAPI, using fallback logic. Error: {}", e.getMessage());
        }

        // 통신 실패 시 원본 메시지 기준 최대 길이 제한 적용
        return userMessage.length() > MAX_TITLE_LENGTH
                ? userMessage.substring(0, MAX_TITLE_LENGTH - 3) + "..."
                : userMessage;
    }

    /**
     *  채팅 세션 타이틀 수동 변경
     */
    @Transactional
    public void updateSessionTitle(Long userId, Long sessionId, String newTitle) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND_SESSION));

        // 세션 소유자 일치 여부 확인 (인가 검증)
        if (!session.getUser().getId().equals(userId)) {
            log.warn("[ChatService] Forbidden access attempt. UserId: {}, SessionId: {}", userId, sessionId);
            throw new CustomException(ErrorCode.NOT_SESSION_OWNER);
        }

        session.updateTitle(newTitle);
        log.info("[ChatService] Session title updated. SessionId: {}, NewTitle: {}", sessionId, newTitle);
    }

    /**
     * 최근 대화 내역 조회 및 DTO 변환
     */
    private List<ChatHistoryDto> getChatHistory(Long sessionId, int limit) {
        if (sessionId == null) return Collections.emptyList();

        // 해당 세션의 최근 대화 내역 조회
        List<ChatMessage> messages = chatMessageRepository.findRecentMessages(sessionId, limit);

        // DTO 변환 (시간순 정렬을 위해 수정 가능한 ArrayList로 추출)
        List<ChatHistoryDto> dtoList = new ArrayList<>(messages.stream()
                .map(m -> new ChatHistoryDto(
                        m.getChatRole() == ChatRole.USER ? "user" : "model",
                        m.getContent()
                ))
                .toList());

        // 대화 흐름을 순서대로 파악할 수 있게 시간순으로 재배치
        Collections.reverse(dtoList);
        return dtoList;
    }

    /**
     * 채팅 메시지 저장
     */
    private void saveChatMessage(ChatSession session, ChatRole role, String content) {
        chatMessageRepository.save(ChatMessage.builder()
                .chatSession(session)
                .chatRole(role)
                .content(content)
                .build());
    }

    /**
     * FastAPI 서버에 메시지 임베딩 요청 및 응답 반환
     */
    private float[] fetchQueryEmbedding(List<ChatHistoryDto> history, String userMessage) {
        try {
            QueryEmbeddingResponse response = restClient.post()
                    .uri(props.fastapiUrl() + "/api/genai/chat/embeddings/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new QueryEmbeddingRequest(history, userMessage))
                    .retrieve()
                    .body(QueryEmbeddingResponse.class);

            if (response == null || response.vector() == null) {
                log.error("[ChatService] Invalid response from FastAPI embedding endpoint.");
                throw new CustomException(ErrorCode.FASTAPI_COMMUNICATION_FAILED);
            }
            return response.vector();

        } catch (RestClientException e) {
            log.error("[ChatService] Failed to communicate with FastAPI for query embedding.", e);
            throw new CustomException(ErrorCode.FASTAPI_COMMUNICATION_FAILED);
        }
    }
    /**
     * FastAPI 서버에 RAG 프롬프트 기반 텍스트 생성 요청
     */
    private String fetchGeneratedChat(List<ChatHistoryDto> history, String userMessage, List<String> contexts) {
        try {
            RagGenerationResponse response = restClient.post()
                    .uri(props.fastapiUrl() + "/api/genai/chat/title/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RagGenerationRequest(history, userMessage, contexts))
                    .retrieve()
                    .body(RagGenerationResponse.class);

            if (response == null || response.reply() == null) {
                log.error("[ChatService] Invalid response from FastAPI generation endpoint.");
                throw new CustomException(ErrorCode.FASTAPI_COMMUNICATION_FAILED);
            }
            return response.reply();

        } catch (RestClientException e) {
            log.error("[ChatService] Failed to communicate with FastAPI for RAG chat generation.", e);
            throw new CustomException(ErrorCode.FASTAPI_COMMUNICATION_FAILED);
        }
    }
}