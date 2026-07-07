package io.github.seoleeder.owls_pick.service.genai.localization;

import io.github.seoleeder.owls_pick.dto.response.KeywordLocalizationBulkResponse;
import io.github.seoleeder.owls_pick.entity.game.KeywordDictionary;
import io.github.seoleeder.owls_pick.entity.game.Tag;
import io.github.seoleeder.owls_pick.entity.genai.GenaiFailedTask;
import io.github.seoleeder.owls_pick.entity.genai.enums.GenaiPipelineType;
import io.github.seoleeder.owls_pick.global.config.properties.GenaiProperties;
import io.github.seoleeder.owls_pick.repository.GenaiFailedTaskRepository;
import io.github.seoleeder.owls_pick.repository.KeywordDictionaryRepository;
import io.github.seoleeder.owls_pick.repository.TagRepository;
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
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class KeywordLocalizationServiceTest {

    @InjectMocks
    private KeywordLocalizationService keywordLocalizationService;

    @Mock
    private KeywordDictionaryRepository dictionaryRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private GenaiFailedTaskRepository failedTaskRepository;

    @Mock
    private RestClient localizationRestClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private RestClient.RequestBodySpec requestBodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    @Mock
    private GenaiProperties genaiProperties;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Captor
    private ArgumentCaptor<List<KeywordDictionary>> dictionaryListCaptor;

    @BeforeEach
    void setUp() {
        // TransactionTemplate의 반환값 유무에 따른 콜백 실행 모킹
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        // GenaiProperties 초기화 및 주입
        lenient().when(genaiProperties.fastapiUrl()).thenReturn("http://localhost:8000");
        GenaiProperties.Localization.ChunkSize chunkSize = new GenaiProperties.Localization.ChunkSize(10, 100);
        GenaiProperties.Localization localization = new GenaiProperties.Localization(chunkSize, 1000);
        lenient().when(genaiProperties.localization()).thenReturn(localization);

        // RestClient 체이닝 명시적 조립 및 모킹
        lenient().when(localizationRestClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(any(URI.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("신규 키워드 추출 시 키워드 사전에 존재하는 단어 제외 및 저장 확인")
    void extractNewKeywords_FilterExisting_SaveOnlyNew() {
        // [Given] 전체 고유 태그 목록 및 이미 사전에 등록된 단어 목록 세팅
        when(tagRepository.findAllDistinctKeywords()).thenReturn(List.of("Action", "Fantasy", "NewWord"));
        when(dictionaryRepository.findExistingEngNames(anyList())).thenReturn(List.of("Action", "Fantasy"));

        // [When] 키워드 파이프라인 단건 모드 실행
        keywordLocalizationService.runPipeline(100, true);

        // [Then] ArgumentCaptor를 활용하여 중복 단어 제외 후 신규 단어 단 1건만 저장됨을 검증
        verify(dictionaryRepository, times(1)).saveAll(dictionaryListCaptor.capture());

        List<KeywordDictionary> savedList = dictionaryListCaptor.getValue();
        assertThat(savedList).hasSize(1);
        assertThat(savedList.get(0).getEngName()).isEqualTo("NewWord");
    }

    @Test
    @DisplayName("한글화가 누락된 키워드 동기화 시 영문 원본 유지 확인")
    void applyLocalizationsToTags_MissingTranslation_KeepEnglish() {
        // [Given] 한글화된 단어와 한글화되지 않은 단어가 혼재된 사전 및 태그 데이터 세팅
        KeywordDictionary translatedDict = KeywordDictionary.builder().engName("Action").korName("액션").build();
        when(dictionaryRepository.findAll()).thenReturn(List.of(translatedDict));

        Tag mockTag = mock(Tag.class);
        when(mockTag.getKeywords()).thenReturn(List.of("Action", "Missing"));

        when(tagRepository.findTagsNeedingKeywordLocalization(100))
                .thenReturn(List.of(mockTag))
                .thenReturn(List.of());

        // [When] 키워드 파이프라인 단건 모드 실행
        keywordLocalizationService.runPipeline(100, true);

        // [Then] 한글화 누락 시 null 할당 방지 및 영문 원본 보존(Fallback) 확인
        verify(mockTag, times(1)).updateKeywordsKo(List.of("액션", "Missing"));
    }

    @Test
    @DisplayName("처리 대상이 청크 크기를 초과할 경우 API 분할 호출 확인")
    void processUnlocalizedKeywords_ExceedChunkSize_MultipleApiCalls() {
        // [Given] 한글화되지 않은 키워드 3건 조회 상황 세팅
        List<KeywordDictionary> list = List.of(
                mock(KeywordDictionary.class),
                mock(KeywordDictionary.class),
                mock(KeywordDictionary.class)
        );
        when(dictionaryRepository.findUnlocalizedKeywords()).thenReturn(list);

        // [Given] API 통신 성공 빈 응답 모킹
        KeywordLocalizationBulkResponse mockResponse = new KeywordLocalizationBulkResponse(List.of());
        when(responseSpec.body(KeywordLocalizationBulkResponse.class)).thenReturn(mockResponse);

        // [When] 청크 크기를 2로 제한하여 키워드 처리 로직 실행
        int processed = keywordLocalizationService.processUnlocalizedKeywords(2, false);

        // [Then] 총 3건 반환 확인 및 설정된 청크 크기에 따른 API 다중 호출(2회 분할) 검증
        assertThat(processed).isEqualTo(3);
        verify(localizationRestClient, times(2)).post();
    }

    @Test
    @DisplayName("키워드 한글화 통신 장애 시 파이프라인 중단 없이 실패 작업 기록 확인")
    void processUnlocalizedKeywords_CommunicationError_RecordFailure() {
        // [Given] 한글화 대상 미번역 키워드 사전 데이터 세팅
        KeywordDictionary mockDict = mock(KeywordDictionary.class);
        when(mockDict.getId()).thenReturn(1L);
        when(dictionaryRepository.findUnlocalizedKeywords()).thenReturn(List.of(mockDict));

        // [Given] 외부 API 통신 타임아웃 예외 발생 상황 모킹
        when(responseSpec.body(KeywordLocalizationBulkResponse.class))
                .thenThrow(new RestClientException("Connection Timeout"));

        // [When] 청크 단위 키워드 처리 로직 실행
        int processed = keywordLocalizationService.processUnlocalizedKeywords(10, true);

        // [Then] 파이프라인 중단을 막기 위한 예외 격리 처리 및 실패 이력 적재 검증
        assertThat(processed).isEqualTo(1);
        verify(failedTaskRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("미조치 실패 작업(FailedTask) 재시도 시 정상 통신 및 조치 상태 갱신 확인")
    void retryFailedTasks_Success() {
        // [Given] 미조치 실패 이력 및 연결된 원본 키워드 사전 데이터 세팅
        GenaiFailedTask mockTask = mock(GenaiFailedTask.class);
        when(mockTask.getId()).thenReturn(100L);
        when(mockTask.getTargetId()).thenReturn(1L);
        when(failedTaskRepository.findUnhandledTasks(GenaiPipelineType.KEYWORD_LOCALIZATION))
                .thenReturn(List.of(mockTask));

        KeywordDictionary mockDict = mock(KeywordDictionary.class);
        when(mockDict.getEngName()).thenReturn("Action");
        when(dictionaryRepository.findAllById(anyList())).thenReturn(List.of(mockDict));

        // [Given] AI 엔진의 재시도 정상 번역 결과 응답 모킹
        KeywordLocalizationBulkResponse mockResponse = new KeywordLocalizationBulkResponse(
                List.of(new KeywordLocalizationBulkResponse.KeywordLocalizationResponse("Action", "액션")));
        when(responseSpec.body(KeywordLocalizationBulkResponse.class)).thenReturn(mockResponse);

        when(failedTaskRepository.findAllById(anyList())).thenReturn(List.of(mockTask));

        // [When] 실패 작업 복구 파이프라인 실행
        keywordLocalizationService.retryFailedTasks();

        // [Then] 사전 데이터 더티 체킹 갱신 및 실패 이력 조치 완료 상태(markAsHandled) 변경 확인
        verify(mockDict, times(1)).updateLocalization("액션");
        verify(mockTask, times(1)).markAsHandled();
    }
}