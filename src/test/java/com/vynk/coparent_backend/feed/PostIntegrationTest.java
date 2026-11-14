package com.vynk.coparent_backend.feed;

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
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fil de la famille, contre la base MySQL locale. Camille est A, Julien B. */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long",
        "app.storage.dir=target/test-storage"
})
@AutoConfigureMockMvc
class PostIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String FAMILY = "{\"name\":\"Famille de Léa\",\"vacationZone\":\"A\"}";
    private static final Path STORAGE = Path.of("target/test-storage");

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
        String posts() {
            return "/api/v1/families/" + id + "/posts";
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
    void aPostWithPhotosIsSharedReactedToAndCommented() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Family family = family(camille);
        join(camille, julien, family);
        byte[] photo = PhotoCleanerTest.withExif(PhotoCleanerTest.jpeg(new BufferedImage(60, 40,
                BufferedImage.TYPE_INT_RGB)), 6);

        String body = publish(camille, family, "  Léa a fait ses premiers tours de vélo. ", List.of(family.childId()),
                true, photo, photo)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("Léa a fait ses premiers tours de vélo."))
                .andExpect(jsonPath("$.firstTime").value(true))
                .andExpect(jsonPath("$.children[0].firstName").value("Léa"))
                .andExpect(jsonPath("$.photos", hasSize(2)))
                // Orientation appliquée : 60 × 40 tourné d'un quart de tour
                .andExpect(jsonPath("$.photos[0].width").value(40))
                .andExpect(jsonPath("$.photos[0].height").value(60))
                .andExpect(jsonPath("$.authorFirstName").value("Camille"))
                .andExpect(jsonPath("$.canDelete").value(true))
                .andExpect(jsonPath("$.canRequestRemoval").value(false))
                .andReturn().getResponse().getContentAsString();
        String postId = JsonPath.read(body, "$.id");
        String photoId = JsonPath.read(body, "$.photos[0].id");

        // Stockées sans métadonnées
        List<Path> files = files(family);
        assertThat(files).hasSize(2);
        assertThat(new String(Files.readAllBytes(files.getFirst()), StandardCharsets.ISO_8859_1))
                .doesNotContain("Exif");

        // Julien la voit, avec la photo
        call(julien, HttpMethod.GET, family.posts(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts", hasSize(1)))
                .andExpect(jsonPath("$.posts[0].canDelete").value(false))
                .andExpect(jsonPath("$.posts[0].canRequestRemoval").value(true))
                .andExpect(jsonPath("$.nextBefore", nullValue()));
        call(julien, HttpMethod.GET, family.posts() + "/" + postId + "/photos/" + photoId, null)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", containsString("immutable")));

        // Réactions : une de chaque sorte par parent, mise et retirée
        call(julien, HttpMethod.PUT, family.posts() + "/" + postId + "/reactions/HEART", null)
                .andExpect(status().isOk());
        call(julien, HttpMethod.PUT, family.posts() + "/" + postId + "/reactions/HEART", null)
                .andExpect(status().isOk());
        call(camille, HttpMethod.PUT, family.posts() + "/" + postId + "/reactions/HEART", null)
                .andExpect(jsonPath("$.reactions[0].kind").value("HEART"))
                .andExpect(jsonPath("$.reactions[0].count").value(2))
                .andExpect(jsonPath("$.reactions[0].mine").value(true))
                .andExpect(jsonPath("$.reactions[1].count").value(0));
        call(camille, HttpMethod.DELETE, family.posts() + "/" + postId + "/reactions/HEART", null)
                .andExpect(jsonPath("$.reactions[0].count").value(1))
                .andExpect(jsonPath("$.reactions[0].mine").value(false));

        // Commentaires
        call(julien, HttpMethod.POST, family.posts() + "/" + postId + "/comments",
                "{\"body\":\" Elle en parlait encore ce soir. \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstName").value("Julien"))
                .andExpect(jsonPath("$.body").value("Elle en parlait encore ce soir."));
        call(julien, HttpMethod.POST, family.posts() + "/" + postId + "/comments", "{\"body\":\"  \"}")
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.GET, family.posts() + "/" + postId + "/comments", null)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].role").value("PARENT_B"));
        call(camille, HttpMethod.GET, family.posts(), null)
                .andExpect(jsonPath("$.posts[0].commentCount").value(1));

        // Seule l'autrice supprime ; les photos disparaissent
        call(julien, HttpMethod.DELETE, family.posts() + "/" + postId, null).andExpect(status().isConflict());
        call(camille, HttpMethod.DELETE, family.posts() + "/" + postId, null).andExpect(status().isNoContent());
        call(julien, HttpMethod.GET, family.posts(), null).andExpect(jsonPath("$.posts", hasSize(0)));
        assertThat(files(family)).isEmpty();
    }

    @Test
    void theOtherParentHidesAPostAndAsksForItsRemoval() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Family family = family(camille);
        join(camille, julien, family);
        String postId = JsonPath.read(publish(camille, family, "Au parc", List.of(), false)
                .andReturn().getResponse().getContentAsString(), "$.id");
        String post = family.posts() + "/" + postId;

        // L'autrice ne demande pas le retrait de sa propre publication
        call(camille, HttpMethod.POST, post + "/removal", null).andExpect(status().isConflict());

        call(julien, HttpMethod.POST, post + "/removal", "{\"reason\":\"PLACE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removal.requestedByRole").value("PARENT_B"))
                .andExpect(jsonPath("$.removal.reason").value("PLACE"))
                .andExpect(jsonPath("$.removal.mine").value(true))
                .andExpect(jsonPath("$.canRequestRemoval").value(false));
        call(julien, HttpMethod.POST, post + "/removal", null).andExpect(status().isConflict());
        // Masquée : plus de réactions ni de commentaires
        call(julien, HttpMethod.PUT, post + "/reactions/BRAVO", null).andExpect(status().isConflict());
        call(camille, HttpMethod.POST, post + "/comments", "{\"body\":\"Pourquoi ?\"}")
                .andExpect(status().isConflict());
        // « À traiter » pour Camille
        call(camille, HttpMethod.GET, "/api/v1/families/" + family.id() + "/to-do", null)
                .andExpect(jsonPath("$.mine", hasSize(1)))
                .andExpect(jsonPath("$.mine[0].kind").value("PUBLICATION_REMOVAL"))
                .andExpect(jsonPath("$.mine[0].targetId").value(postId))
                .andExpect(jsonPath("$.mine[0].subject", startsWith("Retrait demandé : Au parc")));

        call(camille, HttpMethod.GET, post, null)
                .andExpect(jsonPath("$.removal.requestedByRole").value("PARENT_B"))
                .andExpect(jsonPath("$.canDelete").value(true));

        // Camille la garde masquée : la demande est close, la publication reste masquée
        call(julien, HttpMethod.POST, post + "/removal/keep", null).andExpect(status().isConflict());
        call(camille, HttpMethod.POST, post + "/removal/keep", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removal.mine").value(false));
        call(camille, HttpMethod.GET, "/api/v1/families/" + family.id() + "/to-do", null)
                .andExpect(jsonPath("$.mine", hasSize(0)));

        // Julien annule : elle réapparaît
        call(camille, HttpMethod.DELETE, post + "/removal", null).andExpect(status().isConflict());
        call(julien, HttpMethod.DELETE, post + "/removal", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removal", nullValue()))
                .andExpect(jsonPath("$.canRequestRemoval").value(true));

        // Nouvelle demande, puis Camille accepte : supprimée, tâche close
        call(julien, HttpMethod.POST, post + "/removal", null)
                .andExpect(jsonPath("$.removal.reason").value("UNSPECIFIED"));
        call(camille, HttpMethod.DELETE, post, null).andExpect(status().isNoContent());
        call(camille, HttpMethod.GET, "/api/v1/families/" + family.id() + "/to-do", null)
                .andExpect(jsonPath("$.mine", hasSize(0)));
    }

    @Test
    void theFeedIsPagedAndFilteredAndWorksInSolo() throws Exception {
        Account camille = verifiedUser("Camille");
        Family family = family(camille);
        for (int i = 1; i <= 3; i++) {
            publish(camille, family, "Publication " + i, i == 2 ? List.of(family.childId()) : List.of(), i == 2)
                    .andExpect(status().isCreated());
        }

        String first = call(camille, HttpMethod.GET, family.posts() + "?limit=2", null)
                .andExpect(jsonPath("$.posts", hasSize(2)))
                .andExpect(jsonPath("$.posts[0].body").value("Publication 3"))
                .andExpect(jsonPath("$.nextBefore").exists())
                .andReturn().getResponse().getContentAsString();
        String next = JsonPath.read(first, "$.nextBefore");
        call(camille, HttpMethod.GET, family.posts() + "?limit=2&before=" + next, null)
                .andExpect(jsonPath("$.posts", hasSize(1)))
                .andExpect(jsonPath("$.posts[0].body").value("Publication 1"))
                .andExpect(jsonPath("$.nextBefore", nullValue()));

        call(camille, HttpMethod.GET, family.posts() + "?firstTime=true", null)
                .andExpect(jsonPath("$.posts", hasSize(1)))
                .andExpect(jsonPath("$.posts[0].body").value("Publication 2"));
        call(camille, HttpMethod.GET, family.posts() + "?childId=" + family.childId(), null)
                .andExpect(jsonPath("$.posts", hasSize(1)));
    }

    @Test
    void invalidPostsAreRefused() throws Exception {
        Account camille = verifiedUser("Camille");
        Account stranger = verifiedUser("Paul");
        Family family = family(camille);
        Family other = family(stranger);
        byte[] photo = PhotoCleanerTest.jpeg(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB));

        publish(camille, family, "   ", List.of(), false).andExpect(status().isBadRequest());
        publish(camille, family, "x".repeat(2001), List.of(), false).andExpect(status().isBadRequest());
        publish(camille, family, "Sans enfant", List.of(), true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Une première fois concerne au moins un enfant."));
        publish(camille, family, "Autre enfant", List.of(other.childId()), false).andExpect(status().isBadRequest());
        publish(camille, family, null, List.of(), false, "<html>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest());
        publish(camille, family, null, List.of(), false, photo, photo, photo, photo, photo, photo, photo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Une publication compte 6 photos au plus."));
        assertThat(files(family)).isEmpty();

        String postId = JsonPath.read(publish(camille, family, "Au parc", List.of(), false)
                .andReturn().getResponse().getContentAsString(), "$.id");
        publish(stranger, family, "Intrus", List.of(), false).andExpect(status().isNotFound());
        call(stranger, HttpMethod.GET, family.posts(), null).andExpect(status().isNotFound());
        call(stranger, HttpMethod.GET, family.posts() + "/" + postId, null).andExpect(status().isNotFound());
        call(stranger, HttpMethod.GET, other.posts() + "/" + postId + "/comments", null)
                .andExpect(status().isNotFound());
    }

    private ResultActions publish(Account account, Family family, String text, List<String> childIds,
                                  boolean firstTime, byte[]... photos) throws Exception {
        MockMultipartHttpServletRequestBuilder builder = multipart(family.posts());
        for (byte[] photo : photos) {
            builder.file(new MockMultipartFile("photos", "photo.jpg", "image/jpeg", photo));
        }
        if (text != null) {
            builder.param("body", text);
        }
        childIds.forEach(id -> builder.param("childIds", id));
        builder.param("firstTime", String.valueOf(firstTime));
        return mvc.perform(builder.header("Authorization", "Bearer " + account.accessToken()));
    }

    private static List<Path> files(Family family) throws IOException {
        Path folder = STORAGE.resolve("posts").resolve(family.id());
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(folder)) {
            return stream.toList();
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
