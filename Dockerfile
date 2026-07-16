# ==========================================
# 1. 추출 스테이지
# ==========================================

FROM eclipse-temurin:25-jre-alpine AS extractor
WORKDIR /app

# 빌드 스테이지 결과물(단일 .jar 파일) 복사
COPY build/libs/*-SNAPSHOT.jar app.jar

# 단일 .jar 파일을 4개의 논리적 계층(Layer) 구조로 분해
RUN java -Djarmode=layertools -jar app.jar extract

# ==========================================
# 3. 운영 런타임 스테이지 (최종 이미지)
# ==========================================

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# 시스템 전용 그룹/사용자(spring) 생성 및 타임존(KST) 설정, 디렉토리 소유권 변경
RUN addgroup -S spring && adduser -S spring -G spring && \
    apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \
    echo "Asia/Seoul" > /etc/timezone && \
    chown -R spring:spring /app

# 이후 실행될 프로세스의 권한을 지정된 사용자로 제한
USER spring:spring

# 추출 스테이지에서 분리된 외부 라이브러리 계층 복사
COPY --from=extractor /app/dependencies/ ./

# 스프링 부트 로더 계층 복사
COPY --from=extractor /app/spring-boot-loader/ ./

# 스냅샷 의존성 계층 복사
COPY --from=extractor /app/snapshot-dependencies/ ./

# 직접 작성한 비즈니스 로직(소스 코드) 계층 복사
COPY --from=extractor /app/application/ ./

# 컨테이너의 8080 포트 개방 선언
EXPOSE 8080

# ZGC 적용 및 메모리 제한 설정을 포함한 애플리케이션 실행 명령어 정의
ENTRYPOINT ["java", \
            "-Duser.timezone=Asia/Seoul", \
            "-Dnetworkaddress.cache.ttl=5", \
            "-Dsun.net.inetaddr.ttl=5", \
            "-XX:MaxRAMPercentage=75.0", \
            "-XX:+UseZGC", \
            "org.springframework.boot.loader.launch.JarLauncher"]