package sarangbang.site.notification.controller;

import io.jsonwebtoken.ExpiredJwtException;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import sarangbang.site.notification.component.EmitterManager;
import sarangbang.site.notification.dto.FcmTokenRequestDTO;
import sarangbang.site.notification.dto.NotificationResponseDTO;
import sarangbang.site.notification.service.NotificationService;
import sarangbang.site.security.details.CustomUserDetails;
import sarangbang.site.security.jwt.JwtTokenProvider;

import java.util.List;
import java.util.UUID;

@Tag(name = "Notification", description = "알림 기능 관련 API")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/notifications")
@Slf4j
public class NotificationController {

    private final EmitterManager emitterManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final NotificationService notificationService;

    // SSE 구독 (ResponseEntity 예외적으로 사용 X)
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam("token") String token) {
        String userId = jwtTokenProvider.getUserIdFromAccessToken(token); // 사용자 ID 추출
        try{
            return emitterManager.save(userId);
        }catch (AsyncRequestTimeoutException e){
            log.warn("userId: {} Notification async request timed out", userId);
        }

        return null;
    }

    // 알림 리스트 조회
    @GetMapping
    public ResponseEntity<?> getMyNotifications(@AuthenticationPrincipal CustomUserDetails userDetails) {
        try{
            String userId = userDetails.getId();

            List<NotificationResponseDTO> notifications = notificationService.getUserNotifications(userId);
            return ResponseEntity.ok(notifications);
        } catch (ExpiredJwtException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("액세스 토큰이 만료 되었습니다.");
        }
    }

    // 개별 알림 읽음 처리
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<String> markAsRead(@PathVariable UUID notificationId) {
        notificationService.markAsRead(notificationId);
        return ResponseEntity.ok("읽음 처리 완료");
    }

    // 알림 삭제 처리
    @DeleteMapping
    public ResponseEntity<String> deleteNotifications(@AuthenticationPrincipal CustomUserDetails userDetails) {
        String userId = userDetails.getId();
        notificationService.deleteNotifications(userId);
        return ResponseEntity.ok("알림 삭제 완료");
    }

    // 사용자 FCM 토큰 저장
    @PostMapping("/token")
    public ResponseEntity<Void> saveFCMToken(
            @RequestBody FcmTokenRequestDTO requestDTO,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        notificationService.saveFCMToken(requestDTO.getToken(), userDetails.getId());
        return ResponseEntity.ok().build();
    }

    // 사용자 FCM 토큰 삭제
    @DeleteMapping("/token")
    public ResponseEntity<String> deleteFCMToken(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        notificationService.deleteFCMToken(userDetails.getId());
        return ResponseEntity.ok("FCM 토큰이 삭제되었습니다.");
    }


}
