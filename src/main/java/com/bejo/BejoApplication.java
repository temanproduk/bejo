package com.bejo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BejoApplication {

    public static void main(String[] args) {
        SpringApplication.run(BejoApplication.class, args);
    }
}
