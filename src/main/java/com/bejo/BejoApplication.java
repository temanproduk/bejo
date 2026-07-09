package com.bejo;

import com.bejo.util.AppTime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class BejoApplication {

    public static void main(String[] args) {
        // Pin the whole app to WIB (UTC+7) so every LocalDate/LocalDateTime.now()
        // — transaction timestamps, session times, the summary buckets — is wall-clock
        // Jakarta time regardless of the host/container zone (Cloud Run runs in UTC).
        TimeZone.setDefault(TimeZone.getTimeZone(AppTime.ZONE));
        SpringApplication.run(BejoApplication.class, args);
    }
}
