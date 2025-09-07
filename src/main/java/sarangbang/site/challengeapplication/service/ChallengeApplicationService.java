package sarangbang.site.challengeapplication.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sarangbang.site.challengeapplication.dto.ChangeChallengeAppDTO;
import sarangbang.site.challengeapplication.dto.ChallengeApplicationDTO;
import sarangbang.site.challengeapplication.entity.ChallengeApplication;
import sarangbang.site.challenge.entity.Challenge;
import sarangbang.site.challenge.service.ChallengeService;
import sarangbang.site.challengeapplication.dto.ChallengeJoinDTO;
import sarangbang.site.challengeapplication.enums.ChallengeApplyStatus;
import sarangbang.site.challengeapplication.exception.DuplicateApplicationException;
import sarangbang.site.challengeapplication.repository.ChallengeApplicationRepository;
import sarangbang.site.challengemember.entity.ChallengeMember;
import sarangbang.site.challengemember.repository.ChallengeMemberRepository;
import sarangbang.site.challengemember.service.ChallengeMemberService;
import org.springframework.context.ApplicationEventPublisher;
import sarangbang.site.challengeapplication.event.ChallengeMemberAcceptedEvent;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import sarangbang.site.challengeapplication.dto.MyPageApplicationDTO;
import sarangbang.site.file.service.FileStorageService;
import sarangbang.site.notification.constant.NotificationConstant;
import sarangbang.site.notification.service.NotificationService;
import sarangbang.site.user.entity.User;
import sarangbang.site.user.service.UserService;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengeApplicationService {

    private final ChallengeApplicationRepository challengeApplicationRepository;
    private final ChallengeMemberService challengeMemberService;
    private final UserService userService;
    private final ChallengeService challengeService;
    private final ApplicationEventPublisher eventPublisher;
    private final FileStorageService fileStorageService;
    private final NotificationService notificationService;
    private final ChallengeMemberRepository challengeMemberRepository;

    // 챌린지 신청 수락/거부
    @Transactional
    public ChangeChallengeAppDTO changeApplicationStatus(Long appId, ChangeChallengeAppDTO dto, String ownerId) {

        ChallengeApplication app = challengeApplicationRepository.findChallengeApplicationById(appId);
        if(app==null){
            throw new IllegalArgumentException("챌린지 "+appId+"를 찾을 수 없습니다.");
        }
        Optional<ChallengeMember> member = challengeMemberService.getMemberByChallengeId(ownerId, app.getChallenge().getId()); // 특정 챌린지의 id 에서 member 조회
        if(member.isEmpty()){
            throw new IllegalArgumentException("챌린지 멤버 "+member+"를 찾을 수 없습니다.");
        }

        if(!app.getChallengeApplyStatus().equals(ChallengeApplyStatus.PENDING)){
            throw new IllegalStateException("이미 처리된 신청입니다.");
        }

        if(member.get().getRole().equals("owner")) {

            String type;
            String content;

            if(dto.getApplyStatus().equals(ChallengeApplyStatus.REJECTED)) {
                app.updateAppStatus(ChallengeApplyStatus.REJECTED);
                app.updateAppComment(dto.getComment());
                type = NotificationConstant.APPLICATION_REJECTED_TYPE;
                content = NotificationConstant.APPLICATION_REJECTED;
            } else {
                app.updateAppStatus(ChallengeApplyStatus.APPROVED);
                app.updateAppComment(dto.getComment());
                type = NotificationConstant.APPLICATION_APPROVED_TYPE;
                content = NotificationConstant.APPLICATION_APPROVED;
                Optional<ChallengeMember> findMember = challengeMemberService.getMemberByChallengeId(app.getUser().getId(), app.getChallenge().getId());
                if(findMember.isPresent()){
                    throw new IllegalStateException("이미 존재하는 회원입니다.");
                }
                challengeMemberService.saveChallengeMember(app.getUser().getId(), app.getChallenge().getId());
                Challenge challenge = challengeService.getChallengeById(app.getChallenge().getId());
                User user = userService.getUserById(app.getUser().getId());
                ChallengeMemberAcceptedEvent event = new ChallengeMemberAcceptedEvent(
                        challenge.getId(),
                        user.getId(),
                        user.getNickname()
                );
                eventPublisher.publishEvent(event);
            }
            challengeApplicationRepository.save(app);

            ChangeChallengeAppDTO changeApp = new ChangeChallengeAppDTO(app.getChallengeApplyStatus(), app.getComment());

            // 신청자에게 알림 전송
            notificationService.sendNotification(
                    app.getUser().getId(),
                    content,
                    type,
                    "신청서 결과 확인 url..(구현 전)"
            );

            notificationService.sendPushNotification(app.getUser().getId(), content); // 푸시 알림

            return changeApp;

        } else throw new SecurityException("챌린지 방장의 권한이 없습니다.");

    }


    public ChallengeJoinDTO saveChallengeApplication(ChallengeJoinDTO challengeJoinDTO, String userId) {
        log.info("=> 챌린지 신청서 저장 로직 시작. userId: {}, challengeId: {}", userId, challengeJoinDTO.getChallengeId());

        User user = userService.getUserById(userId);

        Challenge challenge = challengeService.getChallengeById(challengeJoinDTO.getChallengeId());

        // DuplicateApplicationException 예외 처리 (거절된 신청서는 재신청 허용)
        if (challengeApplicationRepository.existsByUserAndChallengeAndChallengeApplyStatusNot(user, challenge, ChallengeApplyStatus.REJECTED)) {
            log.warn("!! 이미 참여 신청한 챌린지 (PENDING 또는 APPROVED 상태). userId: {}, challengeId: {}", userId, challenge.getId());
            throw new DuplicateApplicationException("이미 참여 신청한 챌린지입니다.");
        }

        // 거절된 신청서가 있는 경우 기존 신청서를 업데이트
        Optional<ChallengeApplication> existingRejectedApplication = challengeApplicationRepository.findByUserAndChallenge(user, challenge);
        if (existingRejectedApplication.isPresent() && existingRejectedApplication.get().getChallengeApplyStatus() == ChallengeApplyStatus.REJECTED) {
            log.info("... 거절된 신청서 발견. 기존 신청서를 업데이트합니다. applicationId: {}", existingRejectedApplication.get().getId());

            ChallengeApplication rejectedApp = existingRejectedApplication.get();
            rejectedApp.updateApplication(
                challengeJoinDTO.getIntroduction(),
                challengeJoinDTO.getReason(),
                challengeJoinDTO.getCommitment(),
                ChallengeApplyStatus.PENDING
            );

            challengeApplicationRepository.save(rejectedApp);

            ChallengeJoinDTO responseDTO = new ChallengeJoinDTO(
                    rejectedApp.getIntroduction(),
                    rejectedApp.getReason(),
                    rejectedApp.getCommitment(),
                    rejectedApp.getChallengeApplyStatus(),
                    rejectedApp.getChallenge().getId()
            );

            log.info("<= 거절된 신청서 업데이트 완료. userId: {}, applicationId: {}", userId, rejectedApp.getId());
            return responseDTO;
        }

        ChallengeApplication challengeApplication = new ChallengeApplication(
                challengeJoinDTO.getIntroduction(),
                challengeJoinDTO.getReason(),
                challengeJoinDTO.getCommitment(),
                ChallengeApplyStatus.PENDING,
                null,
                user,
                challenge
        );

        log.info("... 챌린지 신청서 DB 저장 완료. userId: {}, applicationId: {}", userId, challengeApplication.getId());

        challengeApplicationRepository.save(challengeApplication);

        ChallengeJoinDTO responseDTO = new ChallengeJoinDTO(
                challengeApplication.getIntroduction(),
                challengeApplication.getReason(),
                challengeApplication.getCommitment(),
                challengeApplication.getChallengeApplyStatus(),
                challengeApplication.getChallenge().getId()
        );

        // 방장에게 알림 전송
        String ownerId = challengeMemberService.findChallengeOwnerByChallengeId(challenge.getId());

        notificationService.sendNotification(
                ownerId,
                NotificationConstant.CHALLENGE_APPLY,
                NotificationConstant.CHALLENGE_APPLY_TYPE,
                "/challenge-manage/" + challenge.getId()
        );

        notificationService.sendPushNotification(ownerId, NotificationConstant.CHALLENGE_APPLY);

        log.info("<= 챌린지 신청서 저장 로직 종료. userId: {}, applicationId: {}", userId, challengeApplication.getId());
        return responseDTO;
    }

    /**
     * 사용자의 특정 챌린지 신청 상태 조회
     * @param challengeId 챌린지 ID
     * @param userId 사용자 ID
     * @return 신청 상태 (PENDING, APPROVED, REJECTED), "OWNER" (방장), 또는 null (신청 안함)
     */
    public String getUserApplicationStatus(Long challengeId, String userId) {
        log.info("=> 사용자 신청 상태 조회. challengeId: {}, userId: {}", challengeId, userId);

        User user = userService.getUserById(userId);
        Challenge challenge = challengeService.getChallengeById(challengeId);

        // 먼저 방장인지 확인 (ChallengeMember에서 role이 "owner"인지 확인)
        Optional<ChallengeMember> memberInfo = challengeMemberService.getMemberByChallengeId(userId, challengeId);
        if (memberInfo.isPresent() && "owner".equals(memberInfo.get().getRole())) {
            log.info("<= 사용자는 챌린지 방장. status: OWNER");
            return "OWNER";
        }

        // 방장이 아닌 경우 신청서 상태 확인
        Optional<ChallengeApplication> application = challengeApplicationRepository.findByUserAndChallenge(user, challenge);

        if (application.isPresent()) {
            ChallengeApplyStatus status = application.get().getChallengeApplyStatus();
            log.info("<= 사용자 신청 상태 조회 완료. status: {}", status);
            return status.name();
        } else {
            log.info("<= 사용자 신청 내역 없음.");
            return null;
        }
    }

    /**
     * 특정 챌린지의 참여 신청 목록 조회 (방장 전용)
     */
    @Transactional(readOnly = true)
    public List<ChallengeApplicationDTO> getChallengeApplications(Long challengeId, String ownerId) {
        log.info("=> 챌린지 참여 신청 목록 조회 시작. challengeId: {}, ownerId: {}", challengeId, ownerId);

        // 방장 권한 확인
        validateOwnerPermission(ownerId, challengeId);

        // PENDING 상태인 신청서만 조회
        List<ChallengeApplication> applications = challengeApplicationRepository
                .findByChallengeIdAndStatusWithUserAndRegion(challengeId, ChallengeApplyStatus.PENDING);

        List<ChallengeApplicationDTO> result = applications.stream()
                .map(app -> ChallengeApplicationDTO.from(app, fileStorageService))
                .collect(Collectors.toList());

        log.info("<= 챌린지 참여 신청 목록 조회 완료. challengeId: {}, 신청 개수: {}", challengeId, result.size());
        return result;
    }

    /**
     * 특정 신청서 상세 조회 (방장 전용)
     */
    @Transactional(readOnly = true)
    public ChallengeApplicationDTO getChallengeApplicationDetail(Long applicationId, String ownerId) {
        log.info("=> 챌린지 신청서 상세 조회 시작. applicationId: {}, ownerId: {}", applicationId, ownerId);

        ChallengeApplication application = challengeApplicationRepository
                .findByIdWithUserAndRegion(applicationId);

        if (application == null) {
            throw new IllegalArgumentException("신청서를 찾을 수 없습니다. applicationId: " + applicationId);
        }

        // 방장 권한 확인
        validateOwnerPermission(ownerId, application.getChallenge().getId());

        ChallengeApplicationDTO result = ChallengeApplicationDTO.from(application, fileStorageService); // 수정

        log.info("<= 챌린지 신청서 상세 조회 완료. applicationId: {}", applicationId);
        return result;
    }

    /**
     * 방장 권한 검증
     */
    private void validateOwnerPermission(String ownerId, Long challengeId) {
        Optional<ChallengeMember> member = challengeMemberService.getMemberByChallengeId(ownerId, challengeId);

        if (member.isEmpty()) {
            throw new IllegalArgumentException("챌린지 멤버를 찾을 수 없습니다.");
        }

        if (!"owner".equals(member.get().getRole())) {
            throw new SecurityException("챌린지 방장만 참여 신청을 조회할 수 있습니다.");
        }
    }

    /**
     * 사용자의 챌린지 신청 내역 조회 (마이페이지용)
     *
     * @param
     * @return
     */
    @Transactional(readOnly = true)
    public List<MyPageApplicationDTO> getMyApplications(String userId) {
        log.info("=> 사용자 챌린지 신청내역 조회 시작. userId: {}", userId);

        try {
            // FETCH JOIN으로 N+1 문제 방지하며 조회
            List<ChallengeApplication> applications = challengeApplicationRepository
                    .findByUserIdWithChallengeAndRegion(userId);

            // Entity -> DTO 변환
            List<MyPageApplicationDTO> result = applications.stream()
                    .map(this::convertToMyPageDTO)
                    .collect(Collectors.toList());

            log.info("<= 사용자 챌린지 신청내역 조회 완료. userId: {}, 건수: {}", userId, result.size());
            return result;

        } catch (Exception e) {
            log.error("사용자 챌린지 신청내역 조회 실패. userId: {}, 오류: {}", userId, e.getMessage());
            throw new RuntimeException("신청내역 조회 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * Entity를 MyPageApplicationDTO로 변환
     */
    private MyPageApplicationDTO convertToMyPageDTO(ChallengeApplication application) {
        Challenge challenge = application.getChallenge();

        // 추가 정보 조회
        int currentParicipants = challengeMemberRepository.countByChallengeId(challenge.getId());
        String imageUrl = generateImageUrl(challenge.getImage());
        String location = challenge.getRegion().getFullAddress();

        return MyPageApplicationDTO.builder()
                // 신청서 정보
                .applicationId(application.getId())
                .introduction(application.getIntroduction())
                .reason(application.getReason())
                .commitment(application.getCommitment())
                .challengeApplyStatus(application.getChallengeApplyStatus())
                .comment(application.getComment())

                // 챌린지 정보
                .challengeId(challenge.getId())
                .challengeTitle(challenge.getTitle())
                .challengeDescription(challenge.getDescription())
                .currentParticipants(currentParicipants)
                .maxParticipants(challenge.getParticipants())
                .imageUrl(imageUrl)
                .location(location)

                // 화면 표시용 데이터
                .challengeDisplayTitle(createDisplayTitle(challenge, currentParicipants))
                .build();
    }

    /**
     * 이미지 URL 생성
     */
    private String generateImageUrl(String imagePath) {
        if (imagePath == null) {
            return null;
        }
        return fileStorageService.generatePresignedUrl(imagePath, Duration.ofMinutes(10));
    }

    /**
     * 화면 표시용 제목 생성
     */
    private String createDisplayTitle(Challenge challenge, int currentParicipants) {
        return String.format("%s [%d/%d]",
                challenge.getTitle(),
                currentParicipants,
                challenge.getParticipants());
    }

}
