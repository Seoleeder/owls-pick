package io.github.seoleeder.owls_pick.controller;

import io.github.seoleeder.owls_pick.dto.request.FcmTokenRequest;
import io.github.seoleeder.owls_pick.dto.response.DiscountNotificationResponse;
import io.github.seoleeder.owls_pick.global.response.CommonResponse;
import io.github.seoleeder.owls_pick.global.security.CustomUserDetails;
import io.github.seoleeder.owls_pick.service.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "할인 알림 API", description = "푸시 알림 및 알림 내역 관리 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "FCM 토큰 등록", description = "사용자 기기의 FCM 토큰을 서버에 등록하거나 갱신합니다. 알림 수신 동의 상태여야만 등록됩니다.")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "등록 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": null,
                                      "error": null
                                    }
                                    """))),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 입력값 형식 (토큰 누락)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": false,
                                      "data": null,
                                      "error": {
                                        "code": "40001",
                                        "message": "Token string is required."
                                      }
                                    }
                                    """))),
            @ApiResponse(
                    responseCode = "403",
                    description = "알림 수신 미동의 유저",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": false,
                                      "data": null,
                                      "error": {
                                        "code": "40301",
                                        "message": "알림 수신에 동의하지 않은 사용자입니다."
                                      }
                                    }
                                    """))),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 내부 오류",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "success": false,
                              "data": null,
                              "error": {
                                "code": "50000",
                                "message": "서버 내부 오류입니다."
                              }
                            }
                            """)))
    })
    @PostMapping("/tokens")
    public CommonResponse<Void> registerToken(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody FcmTokenRequest request
    ) {
        notificationService.registerToken(userDetails.getId(), request.token());
        return CommonResponse.ok(null);
    }

    @Operation(summary = "알림 내역 목록 조회", description = "사용자에게 발송된 알림 내역을 최신순으로 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": [
                                        {
                                          "id": 2,
                                          "title": "할인 알림",
                                          "gameTitle": "Hogwarts Legacy",
                                          "discountRate": 50,
                                          "isRead": false,
                                          "expiryDate": "2026-03-20T23:59:59",
                                          "createdAt": "2026-03-16T10:00:00"
                                        },
                                        {
                                          "id": 1,
                                          "title": "할인 알림",
                                          "gameTitle": "EA SPORTS FC 26",
                                          "discountRate": 30,
                                          "isRead": true,
                                          "expiryDate": "2026-03-18T23:59:59",
                                          "createdAt": "2026-03-15T09:30:00"
                                        }
                                      ],
                                      "error": null
                                    }
                                    """))),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 내부 오류",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "success": false,
                              "data": null,
                              "error": {
                                "code": "50000",
                                "message": "서버 내부 오류입니다."
                              }
                            }
                            """)))
    })
    @GetMapping
    public CommonResponse<List<DiscountNotificationResponse>> getNotifications(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return CommonResponse.ok(notificationService.getNotificationList(userDetails.getId()));
    }

    @Operation(summary = "개별 알림 삭제", description = "알림 목록에서 특정 알림 내역을 삭제합니다.")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "삭제 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": true,
                                      "data": null,
                                      "error": null
                                    }
                                    """))),
            @ApiResponse(
                    responseCode = "403",
                    description = "타인의 알림 삭제 시도",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "success": false,
                                      "data": null,
                                      "error": {
                                        "code": "40302",
                                        "message": "해당 알림에 대한 접근 권한이 없습니다."
                                      }
                                    }
                                    """))),
            @ApiResponse(
                    responseCode = "500",
                    description = "서버 내부 오류",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                            {
                              "success": false,
                              "data": null,
                              "error": {
                                "code": "50000",
                                "message": "서버 내부 오류입니다."
                              }
                            }
                            """)))
    })
    @DeleteMapping("/{notificationId}")
    public CommonResponse<Void> deleteNotification(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long notificationId
    ) {
        notificationService.deleteNotification(userDetails.getId(), notificationId);
        return CommonResponse.ok(null);
    }
}
