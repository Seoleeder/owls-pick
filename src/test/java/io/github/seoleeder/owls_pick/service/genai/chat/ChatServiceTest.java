package io.github.seoleeder.owls_pick.service.genai.chat;

import io.github.seoleeder.owls_pick.dto.request.chat.ChatRequest;
import io.github.seoleeder.owls_pick.dto.response.chat.ChatResponse;
import io.github.seoleeder.owls_pick.dto.response.chat.QueryEmbeddingResponse;
import io.github.seoleeder.owls_pick.dto.response.chat.RagGenerationResponse;
import io.github.seoleeder.owls_pick.dto.response.chat.TitleGenerationResponse;
import io.github.seoleeder.owls_pick.entity.game.VectorEmbedding;
import io.github.seoleeder.owls_pick.entity.user.ChatSession;
import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.ChatMessageRepository;
import io.github.seoleeder.owls_pick.repository.ChatSessionRepository;
import io.github.seoleeder.owls_pick.repository.UserRepository;
import io.github.seoleeder.owls_pick.repository.VectorEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ChatServiceTest {

    @InjectMocks
    private ChatService chatService;

    @Mock
    private ChatTrafficService chatTrafficService;
    @Mock
    private ChatSessionRepository chatSessionRepository;
    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private VectorEmbeddingRepository vectorEmbeddingRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private RestClient restClient;
    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;
    @Mock
    private GenaiProperties genaiProperties;

    @Captor
    private ArgumentCaptor<ChatSession> sessionCaptor;

    private static final Long TEST_USER_ID = 1L;

    @BeforeEach
    void setUp() {
        // 반환형 유무에 따른 TransactionTemplate 콜백 실행 모킹
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        // 프로퍼티 인스턴스 초기화 및 주입
        GenaiProperties.Chat.Traffic.Prefix prefix = new GenaiProperties.Chat.Traffic.Prefix("lock:", "rate:");
        GenaiProperties.Chat.Traffic.Limit limit = new GenaiProperties.Chat.Traffic.Limit(10);
        GenaiProperties.Chat.Traffic traffic = new GenaiProperties.Chat.Traffic(prefix, limit);
        GenaiProperties.Chat chat = new GenaiProperties.Chat(10, traffic);

        lenient().when(genaiProperties.chat()).thenReturn(chat);
        lenient().when(genaiProperties.fastapiUrl()).thenReturn("http://localhost:8000");

        // RestClient 체이닝 명시적 조립 및 모킹
        lenient().when(restClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("정상 RAG 파이프라인 처리 시 데이터 반환 및 분산 락 해제 상태 검증")
    void processRagChat_Success_ReturnResponseAndReleaseLock() {
        // [Given] 신규 세션 요청 및 유저 조회 모킹
        ChatRequest request = new ChatRequest(null, "우주 탐험 게임 추천해줘");
        User mockUser = mock(User.class);
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));

        // [Given] FastAPI 응답(타이틀 생성, 벡터 임베딩, RAG 생성) 분기 세팅
        TitleGenerationResponse titleResponse = new TitleGenerationResponse("우주 게임 추천 세션");
        when(responseSpec.body(eq(TitleGenerationResponse.class))).thenReturn(titleResponse);

        QueryEmbeddingResponse queryResponse = new QueryEmbeddingResponse(new float[]{0.1f, 0.2f});
        when(responseSpec.body(eq(QueryEmbeddingResponse.class))).thenReturn(queryResponse);

        RagGenerationResponse ragResponse = new RagGenerationResponse("아우터 와일즈를 추천합니다.");
        when(responseSpec.body(eq(RagGenerationResponse.class))).thenReturn(ragResponse);

        // [Given] 영속성 계층(세션 DB 저장 및 Vector DB 유사도 검색 결과) 상태 구성
        ChatSession mockSession = mock(ChatSession.class);
        when(mockSession.getId()).thenReturn(100L);
        when(chatSessionRepository.save(any(ChatSession.class))).thenReturn(mockSession);

        VectorEmbedding mockVector = mock(VectorEmbedding.class);
        when(mockVector.getSourceText()).thenReturn("아우터 와일즈 데이터");
        when(vectorEmbeddingRepository.findTopSimilarGames(any(float[].class), anyInt())).thenReturn(List.of(mockVector));

        // [When] RAG 메인 파이프라인 실행
        ChatResponse response = chatService.processRagChat(TEST_USER_ID, request);

        // [Then] 정상 응답 검증 및 분산 락 해제(releaseLock) 정상 호출 검증
        assertThat(response.reply()).isEqualTo("아우터 와일즈를 추천합니다.");
        verify(chatTrafficService, times(1)).releaseLock(TEST_USER_ID);
        verify(chatMessageRepository, times(2)).save(any()); // 사용자 1회, AI 1회 저장 확인
    }

    @Test
    @DisplayName("FastAPI 장애 시 예외 발생 여부와 무관하게 finally 블록에서 분산 락이 해제되는지 확인")
    void processRagChat_ExceptionOccurred_ReleaseLockInFinally() {
        // [Given] 기본 요청 데이터 및 세션 저장 상태 세팅
        ChatRequest request = new ChatRequest(null, "게임 추천");
        User mockUser = mock(User.class);
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));

        ChatSession mockSession = mock(ChatSession.class);
        when(chatSessionRepository.save(any(ChatSession.class))).thenReturn(mockSession);

        // [Given] 임베딩 API 호출 시 통신 예외(타임아웃) 발생 상황 모킹
        when(responseSpec.body(eq(TitleGenerationResponse.class))).thenReturn(new TitleGenerationResponse("제목"));
        when(responseSpec.body(eq(QueryEmbeddingResponse.class))).thenThrow(new RestClientException("Connection Timeout"));

        // [When & Then] 통신 예외 발생 검증
        assertThatThrownBy(() -> chatService.processRagChat(TEST_USER_ID, request))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FASTAPI_COMMUNICATION_FAILED);

        // [Then] 예외 발생 시에도 finally 블록을 통한 분산 락 해제(Deadlock 방지) 호출 검증
        verify(chatTrafficService, times(1)).releaseLock(TEST_USER_ID);
    }

    @Test
    @DisplayName("타이틀 생성 API 실패 시 Fallback 로직(원본 텍스트 자르기)이 적용되는지 검증")
    void processRagChat_TitleApiFail_ApplyFallbackTitle() {
        // [Given] 최대 길이(30자)를 초과하는 입력 문자열 세팅
        String longMessage = "이것은 30자가 넘어가는 아주 긴 문장입니다. 타이틀 생성 서버가 죽었을 때 이 문장이 어떻게 잘리는지 확인해야 합니다.";
        ChatRequest request = new ChatRequest(null, longMessage);
        User mockUser = mock(User.class);
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));

        // [Given] 타이틀 생성 API 통신 예외 발생 모킹 및 후속 로직 진행을 위한 가상 응답 세팅
        when(responseSpec.body(eq(TitleGenerationResponse.class))).thenThrow(new RestClientException("Title API Down"));

        when(responseSpec.body(eq(QueryEmbeddingResponse.class))).thenReturn(new QueryEmbeddingResponse(new float[]{0.1f}));
        when(vectorEmbeddingRepository.findTopSimilarGames(any(), anyInt())).thenReturn(List.of(mock(VectorEmbedding.class)));
        when(responseSpec.body(eq(RagGenerationResponse.class))).thenReturn(new RagGenerationResponse("응답"));
        ChatSession mockSession = mock(ChatSession.class);
        when(chatSessionRepository.save(any())).thenReturn(mockSession);

        // [When] RAG 메인 파이프라인 실행
        chatService.processRagChat(TEST_USER_ID, request);

        // [Then] ArgumentCaptor로 세션 객체를 가로채어 Fallback(문자열 자르기 및 제한) 적용 검증
        verify(chatSessionRepository, times(1)).save(sessionCaptor.capture());
        ChatSession savedSession = sessionCaptor.getValue();

        assertThat(savedSession.getTitle()).endsWith("...");
        assertThat(savedSession.getTitle().length()).isLessThanOrEqualTo(30);
    }

    @Test
    @DisplayName("기존 세션으로 대화 시 타이틀 생성 API 호출이 생략되는지 확인")
    void processRagChat_ExistingSession_SkipTitleGeneration() {
        // [Given] 기존 세션 ID가 포함된 요청 상황 구성
        ChatRequest request = new ChatRequest(100L, "그 게임 플레이 타임은 얼마나 돼?");

        ChatSession existingSession = mock(ChatSession.class);
        when(existingSession.getId()).thenReturn(100L);
        when(chatSessionRepository.findById(100L)).thenReturn(Optional.of(existingSession));

        // [Given] RAG 응답 모킹 (타이틀 생성 API 응답은 제외)
        QueryEmbeddingResponse queryResponse = new QueryEmbeddingResponse(new float[]{0.1f});
        when(responseSpec.body(eq(QueryEmbeddingResponse.class))).thenReturn(queryResponse);

        RagGenerationResponse ragResponse = new RagGenerationResponse("약 15시간입니다.");
        when(responseSpec.body(eq(RagGenerationResponse.class))).thenReturn(ragResponse);

        VectorEmbedding mockVector = mock(VectorEmbedding.class);
        when(mockVector.getSourceText()).thenReturn("아우터 와일즈 데이터");
        when(vectorEmbeddingRepository.findTopSimilarGames(any(float[].class), anyInt())).thenReturn(List.of(mockVector));

        // [When] RAG 메인 파이프라인 실행
        ChatResponse response = chatService.processRagChat(TEST_USER_ID, request);

        // [Then] 정상 응답 검증 및 불필요한 유저 DB 조회, 타이틀 API 호출 생략 검증
        assertThat(response.reply()).isEqualTo("약 15시간입니다.");

        verify(userRepository, never()).findById(anyLong());
        verify(responseSpec, never()).body(eq(TitleGenerationResponse.class));
    }


    @Test
    @DisplayName("벡터 DB 검색 결과 0건 반환 시 NOT_FOUND_GAME 예외 처리 확인")
    void processRagChat_EmptySimilarGames_ThrowException() {
        // [Given] 기존 세션 기반 데이터 요청 상태 구성
        ChatRequest request = new ChatRequest(1L, "게임 추천");
        ChatSession mockSession = mock(ChatSession.class);
        when(chatSessionRepository.findById(1L)).thenReturn(Optional.of(mockSession));

        when(responseSpec.body(eq(QueryEmbeddingResponse.class))).thenReturn(new QueryEmbeddingResponse(new float[]{0.1f}));

        // [Given] Vector DB 연관 게임 검색 시 0건 반환 상태 구성
        when(vectorEmbeddingRepository.findTopSimilarGames(any(), anyInt())).thenReturn(Collections.emptyList());

        // [When & Then] NOT_FOUND_GAME 예외 발생 및 트래픽 제어(락 해제) 정상 호출 동시 검증
        assertThatThrownBy(() -> chatService.processRagChat(TEST_USER_ID, request))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND_GAME);

        verify(chatTrafficService, times(1)).releaseLock(TEST_USER_ID);
    }

    @Test
    @DisplayName("세션 타이틀 수동 변경 시 소유자 불일치(인가 실패) 방어 로직 확인")
    void updateSessionTitle_NotOwner_ThrowException() {
        // [Given] 세션의 실제 소유자 ID(99L)와 요청자 ID(TEST_USER_ID)가 불일치하는 상황 구성
        ChatSession mockSession = mock(ChatSession.class);
        User ownerUser = mock(User.class);
        when(ownerUser.getId()).thenReturn(99L);
        when(mockSession.getUser()).thenReturn(ownerUser);

        when(chatSessionRepository.findById(1L)).thenReturn(Optional.of(mockSession));

        // [When & Then] 타인의 세션 변경 시도 시 NOT_SESSION_OWNER 예외 발생 검증
        assertThatThrownBy(() -> chatService.updateSessionTitle(TEST_USER_ID, 1L, "새로운 제목"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_SESSION_OWNER);
    }
}