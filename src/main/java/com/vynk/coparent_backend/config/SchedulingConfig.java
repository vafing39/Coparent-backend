package com.vynk.coparent_backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled}, used by the nightly purge of deleted accounts. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
