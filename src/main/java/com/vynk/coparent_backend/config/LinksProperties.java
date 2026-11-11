package com.vynk.coparent_backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Settings under {@code app.links}: opening the invitation link ({@code <public-base-url>/invitation?token=…})
 * in the mobile app. Universal Links (iOS) and App Links (Android) only work on a public HTTPS domain that serves
 * the verification files of {@code AppLinksController}; until then the web page opens the app through its scheme.
 */
@Validated
@ConfigurationProperties("app.links")
public record LinksProperties(
        /* Custom scheme of the app: {@code coparent://invitation?token=…}. */
        @NotBlank String appScheme,
        /* {@code <Apple Team ID>.<bundle id>} of each iOS app allowed to open the links. */
        List<String> iosAppIds,
        /* Android applicationId. */
        @NotBlank String androidPackage,
        /* SHA-256 fingerprints of the Android signing certificates ("AB:CD:…"). */
        List<String> androidSha256Fingerprints,
        /* Store pages shown on the web page to people who don't have the app yet; empty = hidden. */
        String appStoreUrl,
        String playStoreUrl
) {

    public LinksProperties {
        iosAppIds = nonBlank(iosAppIds);
        androidSha256Fingerprints = nonBlank(androidSha256Fingerprints);
        appStoreUrl = appStoreUrl == null ? "" : appStoreUrl.trim();
        playStoreUrl = playStoreUrl == null ? "" : playStoreUrl.trim();
    }

    private static List<String> nonBlank(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
}
