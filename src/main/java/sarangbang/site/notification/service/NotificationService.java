package sarangbang.site.notification.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import sarangbang.site.notification.component.EmitterManager;
import sarangbang.site.notification.dto.NotificationResponseDTO;
import sarangbang.site.notification.entity.Notification;
import sarangbang.site.notification.entity.NotificationToken;
import sarangbang.site.notification.repository.NotificationRepository;
import sarangbang.site.notification.repository.NotificationTokenRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final EmitterManager emitterManager;
    private final NotificationTokenRepository notificationTokenRepository;

    /**
     * 알림 생성 및 실시간 전송
     */
    public void sendNotification(String receiverId, String content, String type, String url) {
        // 1. DB 저장
        Notification notification = Notification.builder()
                .receiverId(receiverId)
                .content(content)
                .type(type)
                .url(url)
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();

        Notification saved = notificationRepository.save(notification);

        // 2. SSE 전송
        emitterManager.send(receiverId, NotificationResponseDTO.from(saved));

        // 3. FCM 푸시 알림 전송
        sendPushNotification(receiverId, content);
    }

    /**
     * 로그인한 유저의 전체 알림 조회
     */
    public List<NotificationResponseDTO> getUserNotifications(String userId) {
        return notificationRepository.findByReceiverIdOrderByCreatedAtDesc(userId).stream()
                .map(NotificationResponseDTO::from)
                .collect(Collectors.toList());
    }

    /**
     * 알림 읽음 처리
     */
    @Transactional
    public void markAsRead(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new EntityNotFoundException("알림을 찾을 수 없습니다."));
        notification.updateIsRead(true);
    }

    /**
     *  알림 삭제 처리
     */
    @Transactional
    public void deleteNotifications(String userId) {
        notificationRepository.deleteAllByReceiverId(userId);
    }

    // FCM 토큰 저장
    public void saveFCMToken(String fcmToken, String userId) {

        boolean isExist = notificationTokenRepository.existsByUserIdAndToken(userId, fcmToken);

        if(!isExist) {
            NotificationToken token = NotificationToken.builder()
                    .userId(userId).token(fcmToken).createdAt(LocalDateTime.now()).build();

            notificationTokenRepository.save(token);
        }
    }

    // FCM 토큰 삭제
    public void deleteFCMToken(String userId) {
        List<NotificationToken> tokens = notificationTokenRepository.findAllByUserId(userId);
        notificationTokenRepository.deleteAll(tokens);

    }

    // 알림 전송
    public void sendPushNotification(String receiverId, String content) {
        List<NotificationToken> tokens = notificationTokenRepository.findAllByUserId(receiverId);

        for(NotificationToken token : tokens) {
            try{
                sendMessageTo(token.getToken(), content);
            } catch (FirebaseMessagingException e) {
                log.error("FCM 전송 실패 - token : {}", token.getToken());
            }
        }
    }

    public void sendMessageTo(String targetToken, String content) throws FirebaseMessagingException {
        Message message = Message.builder()
                .setToken(targetToken)
                .setNotification(com.google.firebase.messaging.Notification.builder()
                        .setBody(content).build()).build();

        FirebaseMessaging.getInstance().send(message);
    }
}
