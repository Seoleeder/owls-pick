package io.github.seoleeder.owls_pick.global.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 *  Firebase 설정 매핑
 */
@ConfigurationProperties(prefix = "firebase.config")
public record FirebaseProperties(
        // Firebase Admin SDK 초기화를 위한 서비스 계정 키의 Base64 인코딩 문자열 매핑
        String credentialsBase64
) {
}
