package sarangbang.site.notification.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.FileInputStream;
import java.io.IOException;

// Firebase 초기화 설정
@Profile("prod")
@Configuration
public class FirebaseConfig {

    @Value("${FIREBASE_KEY_PATH}")
    private String firebaseKeyPath;

    @PostConstruct // 앱 시작 시 자동 초기화
    public void initialize() {
        try {
            FileInputStream serviceAccount =
                    new FileInputStream(firebaseKeyPath);

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options);
            }

        } catch (IOException e) {
            throw new RuntimeException("Firebase 키 파일을 찾을 수 없거나 초기화에 실패했습니다. 경로: " + firebaseKeyPath, e);
        }
    }


}
