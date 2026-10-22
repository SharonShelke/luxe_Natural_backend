package com.lichtluxe.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * LICHT LUXE NATURALS — Backend Application
 * Pure. Natural. Made with Love.
 * MADE IN LOVEWORLD
 */
@SpringBootApplication
@EnableAsync
public class LuxeNaturalsBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(LuxeNaturalsBackendApplication.class, args);
    }
}
