package com.vynk.coparent_backend.links;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public routes that open the invitation link in the app: verification files and landing page. */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long",
        "app.links.ios-app-ids=ABCDE12345.fr.coparent.app",
        "app.links.android-package=fr.coparent.app",
        "app.links.android-sha256-fingerprints=AA:BB:CC",
        "app.links.app-store-url=https://apps.apple.com/app/id123"
})
@AutoConfigureMockMvc
class AppLinksIntegrationTest {

    private static final String TOKEN = "q3Jx0w8vT2mY6kZpLs4NbRcHd1EaFgUi5oVtWnXy7Q8";

    @Autowired
    private MockMvc mvc;

    @Test
    void appleAppSiteAssociationIsPublicJson() throws Exception {
        mvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.applinks.details[0].appIDs[0]").value("ABCDE12345.fr.coparent.app"))
                .andExpect(jsonPath("$.applinks.details[0].components[0]['/']").value("/invitation"));
    }

    @Test
    void assetLinksArePublicJson() throws Exception {
        mvc.perform(get("/.well-known/assetlinks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relation[0]").value("delegate_permission/common.handle_all_urls"))
                .andExpect(jsonPath("$[0].target.package_name").value("fr.coparent.app"))
                .andExpect(jsonPath("$[0].target.sha256_cert_fingerprints[0]").value("AA:BB:CC"));
    }

    @Test
    void invitationPageOpensTheAppWithoutLeakingTheToken() throws Exception {
        mvc.perform(get("/invitation").param("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(containsString("href=\"coparent://invitation?token=" + TOKEN + "\"")))
                .andExpect(content().string(containsString("https://apps.apple.com/app/id123")));
    }

    @Test
    void invitationPageWithInvalidTokenHasNoAppLink() throws Exception {
        mvc.perform(get("/invitation").param("token", "\"><script>alert(1)</script>"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lien incomplet")))
                .andExpect(content().string(not(containsString("coparent://"))))
                .andExpect(content().string(not(containsString("<script>alert"))));
    }
}
