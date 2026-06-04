package io.github.seoleeder.owls_pick.service;

import io.github.seoleeder.owls_pick.dto.request.NotificationToggleRequest;
import io.github.seoleeder.owls_pick.dto.request.OnboardingRequest;
import io.github.seoleeder.owls_pick.entity.user.User;
import io.github.seoleeder.owls_pick.global.response.CustomException;
import io.github.seoleeder.owls_pick.global.response.ErrorCode;
import io.github.seoleeder.owls_pick.repository.FcmTokenRepository;
import io.github.seoleeder.owls_pick.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UserProfileServiceTest {

    @InjectMocks
    private UserProfileService userProfileService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FcmTokenRepository fcmTokenRepository;

    @Test
    @DisplayName("이미 온보딩을 마친 유저의 재요청 시 예외 발생 검증")
    void completeOnboarding_AlreadyOnboarded_ThrowException() {
        // [Given] 온보딩 완료 상태(isOnboarded = true)의 유저 객체 모킹
        User onboardedUser = mock(User.class);
        when(onboardedUser.isOnboarded()).thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(onboardedUser));

        OnboardingRequest request = new OnboardingRequest("newNickname", LocalDate.now(), List.of(), List.of());

        // [When & Then] 온보딩 재요청 시 ALREADY_ONBOARDED 예외 발생 확인
        assertThatThrownBy(() -> userProfileService.completeOnboarding(1L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ErrorCode.ALREADY_ONBOARDED.getMessage());
    }

    @Test
    @DisplayName("온보딩 시 중복된 닉네임 입력에 대한 예외 발생 검증")
    void completeOnboarding_DuplicateNickname_ThrowException() {
        // [Given] 온보딩 미완료 상태의 정상 유저 세팅
        User newUser = mock(User.class);
        when(newUser.isOnboarded()).thenReturn(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(newUser));

        // [Given] 특정 닉네임에 대한 중복 검사 결과(true) 세팅
        String duplicateName = "existingUser";
        when(userRepository.existsByNickname(duplicateName)).thenReturn(true);

        OnboardingRequest request = new OnboardingRequest(duplicateName, LocalDate.now(), List.of(), List.of());

        // [When & Then] 온보딩 요청 시 DUPLICATE_NICKNAME 예외 발생 확인
        assertThatThrownBy(() -> userProfileService.completeOnboarding(2L, request))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining(ErrorCode.DUPLICATE_NICKNAME.getMessage());
    }

    @Test
    @DisplayName("할인 알림 비동의(false) 시 FCM 토큰 일괄 파기 로직 호출 검증")
    void toggleDiscountNotification_Disable_DeleteFcmTokens() {
        // [Given] 알림 비동의(false) 요청 상태 세팅
        User mockUser = mock(User.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));

        NotificationToggleRequest request = new NotificationToggleRequest(false);

        // [When] 알림 수신 상태 변경 로직 실행
        userProfileService.toggleDiscountNotification(1L, request);

        // [Then] 상태 업데이트 정상 호출 및 FCM 토큰 일괄 삭제 동작 확인
        verify(mockUser, times(1)).updateDiscountNotification(false);
        verify(fcmTokenRepository, times(1)).deleteAllByUserId(1L);
    }

    @Test
    @DisplayName("할인 알림 동의(true) 시 FCM 토큰 파기 로직 미호출 검증")
    void toggleDiscountNotification_Enable_DoNotDeleteFcmTokens() {
        // [Given] 알림 수신 동의(true) 요청 상태 세팅
        User mockUser = mock(User.class);
        when(userRepository.findById(2L)).thenReturn(Optional.of(mockUser));

        NotificationToggleRequest request = new NotificationToggleRequest(true);

        // [When] 알림 수신 상태 변경 로직 실행
        userProfileService.toggleDiscountNotification(2L, request);

        // [Then] 상태 업데이트 정상 호출 및 토큰 일괄 삭제 로직 미호출(0회) 확인
        verify(mockUser, times(1)).updateDiscountNotification(true);
        verify(fcmTokenRepository, never()).deleteAllByUserId(anyLong());
    }
}