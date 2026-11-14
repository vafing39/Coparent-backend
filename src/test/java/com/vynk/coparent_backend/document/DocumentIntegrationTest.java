package com.vynk.coparent_backend.document;

import com.jayway.jsonpath.JsonPath;
import com.vynk.coparent_backend.auth.email.EmailSender;
import com.vynk.coparent_backend.auth.service.RateLimiter;
import com.vynk.coparent_backend.common.storage.PhotoCleanerTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Documents partagés, contre la base MySQL locale. Camille est A, Julien B. */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long",
        "app.storage.dir=target/test-storage"
})
@AutoConfigureMockMvc
class DocumentIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String FAMILY = "{\"name\":\"Famille de Léa\",\"vacationZone\":\"A\"}";
    private static final Path STORAGE = Path.of("target/test-storage");
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj << /Author (Camille) >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RateLimiter rateLimiter;
    @MockitoBean
    private EmailSender emailSender;

    private record Account(String id, String accessToken) {
    }

    private record Family(String id, String childId) {
        String documents() {
            return "/api/v1/families/" + id + "/documents";
        }
    }

    @BeforeEach
    void resetRateLimits() {
        rateLimiter.reset();
    }

    @AfterEach
    void deleteTestData() {
        jdbc.update("DELETE f FROM families f JOIN family_members m ON m.family_id = f.id "
                + "JOIN users u ON u.id = m.user_id WHERE u.email LIKE ?", "%" + TEST_DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + TEST_DOMAIN);
    }

    @Test
    void aPdfIsSharedOpenedByALoggedTemporaryLinkThenDeletedByItsAuthor() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Family family = family(camille);
        join(camille, julien, family);

        String body = upload(camille, family, PDF, "  Jugement du 14 mars 2025 ", "JUDGMENT", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Jugement du 14 mars 2025"))
                .andExpect(jsonPath("$.category").value("JUDGMENT"))
                .andExpect(jsonPath("$.childId", nullValue()))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(PDF.length))
                .andExpect(jsonPath("$.authorFirstName").value("Camille"))
                .andExpect(jsonPath("$.canDelete").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        assertThat(files(family)).hasSize(1);

        call(julien, HttpMethod.GET, family.documents(), null)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].canDelete").value(false))
                .andExpect(jsonPath("$[0].history", hasSize(0)));

        // Julien l'ouvre : un lien temporaire, sans session, et la consultation entre dans l'historique
        String path = JsonPath.read(call(julien, HttpMethod.POST, family.documents() + "/" + id + "/open", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path", startsWith("/api/v1/document-files/")))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString(), "$.path");
        byte[] served = mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(PDF);
        mvc.perform(get(path + "x")).andExpect(status().isNotFound());

        call(camille, HttpMethod.GET, family.documents(), null)
                .andExpect(jsonPath("$[0].history", hasSize(1)))
                .andExpect(jsonPath("$[0].history[0].kind").value("VIEWED"))
                .andExpect(jsonPath("$[0].history[0].role").value("PARENT_B"))
                .andExpect(jsonPath("$[0].history[0].firstName").value("Julien"));

        // Seule Camille le supprime ; il reste dans la liste, sans fichier ni lien
        call(julien, HttpMethod.DELETE, family.documents() + "/" + id, null).andExpect(status().isConflict());
        call(camille, HttpMethod.DELETE, family.documents() + "/" + id, null).andExpect(status().isNoContent());
        assertThat(files(family)).isEmpty();
        mvc.perform(get(path)).andExpect(status().isNotFound());
        call(julien, HttpMethod.POST, family.documents() + "/" + id + "/open", null)
                .andExpect(status().isNotFound());
        call(julien, HttpMethod.GET, family.documents(), null)
                .andExpect(jsonPath("$[0].deletedAt").exists())
                .andExpect(jsonPath("$[0].deletedByRole").value("PARENT_A"))
                .andExpect(jsonPath("$[0].canDelete").value(false))
                .andExpect(jsonPath("$[0].history", hasSize(2)))
                .andExpect(jsonPath("$[0].history[0].kind").value("DELETED"));
        call(camille, HttpMethod.DELETE, family.documents() + "/" + id, null).andExpect(status().isNotFound());
    }

    @Test
    void aPhotoIsStoredWithoutItsMetadataForOneChild() throws Exception {
        Account camille = verifiedUser("Camille");
        Family family = family(camille);
        byte[] photo = PhotoCleanerTest.withExif(PhotoCleanerTest.jpeg(new BufferedImage(60, 40,
                BufferedImage.TYPE_INT_RGB)), 6);

        // en solo aussi
        String id = JsonPath.read(upload(camille, family, photo, "Certificat de scolarité", "SCHOOL",
                        family.childId())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.childId").value(family.childId()))
                .andExpect(jsonPath("$.childFirstName").value("Léa"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        byte[] stored = Files.readAllBytes(files(family).getFirst());
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");

        String path = JsonPath.read(call(camille, HttpMethod.POST, family.documents() + "/" + id + "/open", null)
                .andReturn().getResponse().getContentAsString(), "$.path");
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Content-Disposition", containsString(".jpg")));
    }

    @Test
    void invalidDocumentsAreRefused() throws Exception {
        Account camille = verifiedUser("Camille");
        Account stranger = verifiedUser("Inconnu");
        Family family = family(camille);
        Family other = family(stranger);

        upload(camille, family, "<html>".getBytes(StandardCharsets.UTF_8), "Page", "OTHER", null)
                .andExpect(status().isBadRequest());
        upload(camille, family, PDF, "   ", "OTHER", null).andExpect(status().isBadRequest());
        upload(camille, family, PDF, "x".repeat(121), "OTHER", null).andExpect(status().isBadRequest());
        upload(camille, family, PDF, "Passeport", "PASSPORT", null).andExpect(status().isBadRequest());
        upload(camille, family, PDF, "Passeport", "IDENTITY", other.childId()).andExpect(status().isBadRequest());
        upload(stranger, family, PDF, "Passeport", "IDENTITY", null).andExpect(status().isNotFound());
        assertThat(files(family)).isEmpty();

        String id = JsonPath.read(upload(camille, family, PDF, "Passeport", "IDENTITY", null)
                .andReturn().getResponse().getContentAsString(), "$.id");
        call(stranger, HttpMethod.GET, family.documents(), null).andExpect(status().isNotFound());
        call(stranger, HttpMethod.POST, other.documents() + "/" + id + "/open", null)
                .andExpect(status().isNotFound());
        call(stranger, HttpMethod.DELETE, other.documents() + "/" + id, null).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/document-files/pas-un-jeton")).andExpect(status().isNotFound());
        mvc.perform(request(HttpMethod.GET, family.documents())).andExpect(status().isUnauthorized());
    }

    private ResultActions upload(Account account, Family family, byte[] bytes, String title, String category,
                                 String childId) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart(family.documents())
                .file(new MockMultipartFile("file", "document", "application/octet-stream", bytes));
        builder.param("title", title).param("category", category);
        if (childId != null) {
            builder.param("childId", childId);
        }
        return mvc.perform(builder.header("Authorization", "Bearer " + account.accessToken()));
    }

    private static List<Path> files(Family family) throws IOException {
        Path folder = STORAGE.resolve("documents").resolve(family.id());
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.toList();
        }
    }

    private Account verifiedUser(String firstName) throws Exception {
        String email = "user-" + UUID.randomUUID() + TEST_DOMAIN;
        String body = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"confirmPassword\":\""
                                + PASSWORD + "\",\"firstName\":\"" + firstName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE users SET email_verified_at = UTC_TIMESTAMP(6) WHERE email = ?", email);
        return new Account(JsonPath.read(body, "$.user.id"), JsonPath.read(body, "$.accessToken"));
    }

    /** Famille de l'utilisateur, avec un enfant, Léa. */
    private Family family(Account account) throws Exception {
        String body = call(account, HttpMethod.POST, "/api/v1/families", FAMILY)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String familyId = JsonPath.read(body, "$.id");
        String child = call(account, HttpMethod.POST, "/api/v1/families/" + familyId + "/children",
                "{\"firstName\":\"Léa\",\"birthDate\":\"2018-03-12\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Family(familyId, JsonPath.read(child, "$.id"));
    }

    private void join(Account inviter, Account invitee, Family family) throws Exception {
        String invitation = call(inviter, HttpMethod.POST, "/api/v1/families/" + family.id() + "/invitations",
                "{\"inviteeFirstName\":\"Julien\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String link = JsonPath.read(invitation, "$.link");
        String token = link.substring(link.indexOf("token=") + "token=".length());
        call(invitee, HttpMethod.POST, "/api/v1/invitations/" + token + "/accept", null).andExpect(status().isOk());
    }

    private ResultActions call(Account account, HttpMethod method, String path, String json) throws Exception {
        var builder = request(method, path).header("Authorization", "Bearer " + account.accessToken());
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(builder);
    }
}
