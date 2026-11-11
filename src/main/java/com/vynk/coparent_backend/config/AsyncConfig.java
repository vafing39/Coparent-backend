package com.vynk.coparent_backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Enables {@code @Async}, used so HTTP responses don't wait for slow work such as sending emails. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
