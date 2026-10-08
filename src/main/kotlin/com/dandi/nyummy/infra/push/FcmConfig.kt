package com.dandi.nyummy.infra.push

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Base64

@Configuration
@EnableConfigurationProperties(FcmProperties::class)
@ConditionalOnProperty(prefix = "app.fcm", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class FcmConfig {
    @Bean
    fun firebaseMessaging(fcmProperties: FcmProperties): FirebaseMessaging {
        val credentials = Base64.getDecoder()
            .decode(fcmProperties.credentialsBase64)
            .inputStream()
            .use { GoogleCredentials.fromStream(it) }

        val app = FirebaseApp.getApps().firstOrNull()
            ?: FirebaseApp.initializeApp(
                FirebaseOptions.Builder().setCredentials(credentials).build(),
            )

        return FirebaseMessaging.getInstance(app)
    }
}
