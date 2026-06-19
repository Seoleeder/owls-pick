package io.github.seoleeder.owls_pick.global.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import io.github.seoleeder.owls_pick.global.config.properties.FirebaseProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;


/**
 * Firebase Admin SDK의 전역 설정 담당 클래스
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class FirebaseConfig {
    private final FirebaseProperties firebaseProperties;

    /**
     * 서버 가동 시 Firebase Admin SDK 초기화
     */
    @PostConstruct
    public void init() {
        try {
            // 환경 변수로 주입받은 Base64 문자열을 디코딩하여 메모리 스트림으로 생성
            byte[] decodedBytes = Base64.getDecoder().decode(firebaseProperties.credentialsBase64());
            InputStream serviceAccount = new ByteArrayInputStream(decodedBytes);

            // 파일 데이터를 구글 서비스 인증에 사용할 수 있는 형태로 변환하고 설정 구성
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();

            // 현재 등록된 Firebase 앱 인스턴스가 있는지 확인
            if (FirebaseApp.getApps().isEmpty()) {
                // 작성된 설정을 바탕으로 Firebase 서버와 통신할 수 있는 앱 초기화
                FirebaseApp.initializeApp(options);
                log.info("Firebase Admin SDK 초기화 성공 - Owl's Pick");
            }
        } catch (IOException e) {
            // 오류 발생 시 서버 구동 중단
            log.error("Firebase Admin SDK 초기화 실패: {}", e.getMessage());
            throw new RuntimeException("Firebase 초기화 에러", e);
        }
    }
}
