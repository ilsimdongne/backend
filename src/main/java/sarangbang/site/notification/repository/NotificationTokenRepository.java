package sarangbang.site.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sarangbang.site.notification.entity.NotificationToken;

import java.util.List;

@Repository
public interface NotificationTokenRepository extends JpaRepository<NotificationToken, Long> {
    boolean existsByUserIdAndToken(String userId, String token);
    List<NotificationToken> findAllByUserId(String userId);
}
