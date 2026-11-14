package com.vynk.coparent_backend.common.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Settings under {@code app.storage} in application.yaml: the folder of {@link DiskFileStorage}. */
@Validated
@ConfigurationProperties("app.storage")
public record StorageProperties(@NotBlank String dir) {
}
