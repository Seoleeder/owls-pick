package io.github.seoleeder.owls_pick.global.response;

import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

@Slf4j
// Actuator 모니터링 충돌 방지를 위해 비즈니스 API 컨트롤러 한정
@RestControllerAdvice(basePackages = "io.github.seoleeder.owls_pick.controller")
public class GlobalExceptionHandler {
    // 존재하지 않는 요청에 대한 예외
    @ExceptionHandler(value = {NoHandlerFoundException.class, HttpRequestMethodNotSupportedException.class})
    public CommonResponse<?> handleNoPageFoundException(Exception e) {
        log.error("GlobalExceptionHandler catch NoHandlerFoundException : {}", e.getMessage());
        return CommonResponse.fail(ErrorCode.NOT_FOUND_END_POINT);
    }


    // 커스텀 예외
    @ExceptionHandler(value = {CustomException.class})
    public CommonResponse<?> handleCustomException(CustomException e) {
        log.error("handleCustomException() in GlobalExceptionHandler throw CustomException : {}", e.getMessage());
        return CommonResponse.fail(e.getErrorCode());
    }

    // 파라미터 타입 미스매치 예외 (Enum 변환 실패 등)
    @ExceptionHandler(value = {MethodArgumentTypeMismatchException.class})
    public CommonResponse<?> handleTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.error("handleTypeMismatchException() in GlobalExceptionHandler : {}", e.getMessage());
        // ErrorCode에 BAD_REQUEST나 INVALID_PARAMETER 같은 400 에러 코드가 있다면 그걸 넣어주세요!
        return CommonResponse.fail(ErrorCode.INVALID_PARAMETER);
    }
    // @Valid 어노테이션으로 인한 DTO 검증 실패 예외
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CommonResponse<?> handleValidationException(MethodArgumentNotValidException e) {
        // DTO에 정의된 여러 에러 중 첫 번째 에러의 메시지를 가져옵니다.
        // (예: "닉네임은 2자 이상 20자 이하로 입력해주세요.")
        String errorMessage = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Invalid input value");

        log.error("handleValidationException() in GlobalExceptionHandler : {}", errorMessage);

        return CommonResponse.fail(ErrorCode.INVALID_PARAMETER);
    }

    // 기본 예외
    @ExceptionHandler(value = {Exception.class})
    public CommonResponse<?> handleException(Exception e) {
        log.error("handleException() in GlobalExceptionHandler throw Exception : {}", e.getMessage());
        e.printStackTrace();
        return CommonResponse.fail(ErrorCode.INTERNAL_SERVER_ERROR);
    }
}
