package com.vynk.coparent_backend.links.controller;

import com.vynk.coparent_backend.config.LinksProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Public routes that let the invitation link open the mobile app: the Universal Links (iOS) and App Links (Android)
 * verification files, and the web page the link lands on when the app doesn't open it (no app yet, or no verified
 * domain). The page shows nothing about the family: the app fetches the preview itself.
 */
@RestController
@RequiredArgsConstructor
public class AppLinksController {

    /** Invitation tokens are 32 random bytes in base64url (43 characters). */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,128}");
    private static final String INVITATION_PATH = "/invitation";

    private final LinksProperties links;

    @GetMapping(value = "/.well-known/apple-app-site-association", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> appleAppSiteAssociation() {
        Map<String, Object> details = Map.of(
                "appIDs", links.iosAppIds(),
                "components", List.of(Map.of("/", INVITATION_PATH)));
        return verificationFile(Map.of("applinks", Map.of("details", List.of(details))));
    }

    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> assetLinks() {
        Map<String, Object> target = Map.of(
                "namespace", "android_app",
                "package_name", links.androidPackage(),
                "sha256_cert_fingerprints", links.androidSha256Fingerprints());
        return verificationFile(List.of(Map.of(
                "relation", List.of("delegate_permission/common.handle_all_urls"),
                "target", target)));
    }

    @GetMapping(value = INVITATION_PATH, produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> invitationPage(@RequestParam(required = false) String token) {
        boolean valid = token != null && TOKEN.matcher(token).matches();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                // The token is a secret: never sent to another site, never indexed.
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex")
                .body(invitationHtml(valid ? token : null));
    }

    private static <T> ResponseEntity<T> verificationFile(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1))).body(body);
    }

    private String invitationHtml(String token) {
        StringBuilder main = new StringBuilder();
        if (token != null) {
            String appLink = links.appScheme() + "://invitation?token=" + token; // token checked: [A-Za-z0-9_-]
            main.append("""
                    <h1>Vous avez reçu une invitation</h1>
                    <p>Ouvrez-la dans l'application pour voir qui vous invite et rejoindre la famille.</p>
                    <a class="button" href="%s">Ouvrir dans l'application</a>
                    """.formatted(HtmlUtils.htmlEscape(appLink)));
            String stores = storeLinks();
            if (!stores.isEmpty()) {
                main.append("<p class=\"secondary\">Pas encore l'application ? Installez-la, puis rouvrez ce lien.</p>")
                        .append(stores);
            }
        } else {
            main.append("""
                    <h1>Lien incomplet</h1>
                    <p>Ce lien d'invitation est incomplet. Demandez un nouveau lien à l'autre parent.</p>
                    """);
        }
        return """
                <!doctype html>
                <html lang="fr">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="robots" content="noindex">
                <title>Invitation</title>
                <style>
                body{margin:0;background:#FAF8F4;color:#24302B;font-family:Nunito,system-ui,sans-serif}
                main{max-width:420px;margin:0 auto;padding:72px 24px 32px;display:flex;flex-direction:column;gap:16px}
                h1{margin:0;font-size:28px;line-height:34px}
                p{margin:0;font-size:15px;line-height:22px}
                .secondary{color:#5E6A64;font-size:13px}
                .button{min-height:52px;border-radius:999px;background:#3F6B5C;color:#FFFFFF;text-decoration:none;
                font-size:17px;font-weight:800;display:flex;align-items:center;justify-content:center}
                .stores{display:flex;gap:16px}
                a{color:#3F6B5C}
                @media (prefers-color-scheme:dark){body{background:#141A17;color:#ECEFEA}.secondary{color:#A3ADA7}
                .button{background:#7FB3A0;color:#141A17}a{color:#7FB3A0}}
                </style>
                </head>
                <body>
                <main>
                %s</main>
                </body>
                </html>
                """.formatted(main);
    }

    private String storeLinks() {
        StringBuilder stores = new StringBuilder();
        if (!links.appStoreUrl().isEmpty()) {
            stores.append("<a href=\"%s\">App Store</a>".formatted(HtmlUtils.htmlEscape(links.appStoreUrl())));
        }
        if (!links.playStoreUrl().isEmpty()) {
            stores.append("<a href=\"%s\">Google Play</a>".formatted(HtmlUtils.htmlEscape(links.playStoreUrl())));
        }
        return stores.isEmpty() ? "" : "<p class=\"stores\">" + stores + "</p>";
    }
}
