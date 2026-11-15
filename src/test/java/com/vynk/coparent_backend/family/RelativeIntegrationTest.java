package com.vynk.coparent_backend.family;

import com.jayway.jsonpath.JsonPath;
import com.vynk.coparent_backend.auth.email.EmailSender;
import com.vynk.coparent_backend.auth.service.RateLimiter;
import com.vynk.coparent_backend.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Famille élargie, contre la base MySQL locale. Camille est A, Julien B, Odile la grand-mère. */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long",
        "app.storage.dir=target/test-storage"
})
@AutoConfigureMockMvc
class RelativeIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String FAMILY = "{\"name\":\"Famille de Léa\",\"vacationZone\":\"A\"}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RateLimiter rateLimiter;
    @MockitoBean
    private EmailSender emailSender;

    private record Account(String id, String email, String accessToken) {
    }

    private record Family(String id, String childId) {
        String path() {
            return "/api/v1/families/" + id;
        }

        String relatives() {
            return path() + "/relatives";
        }

        String posts() {
            return path() + "/posts";
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
    void bothParentsAgreeThenTheRelativeSeesOnlyTheFamilyCircle() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Account odile = verifiedUser("Odile");
        Family family = family(camille);
        join(camille, julien, family);

        // Camille propose, Julien doit valider
        String relativeId = propose(camille, family, "Mamie Odile", odile.email(), "GRANDPARENT")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.proposedByFirstName").value("Camille"))
                .andExpect(jsonPath("$.canApprove").value(false))
                .andReturn().getResponse().getContentAsString();
        relativeId = JsonPath.read(relativeId, "$.id");
        call(julien, HttpMethod.GET, family.path() + "/to-do", null)
                .andExpect(jsonPath("$.mine[0].kind").value("MEMBER"))
                .andExpect(jsonPath("$.mine[0].targetId").value(relativeId));
        call(julien, HttpMethod.GET, family.relatives(), null)
                .andExpect(jsonPath("$[0].canApprove").value(true));
        call(camille, HttpMethod.POST, family.relatives() + "/" + relativeId + "/approve", null)
                .andExpect(status().isConflict());
        verify(emailSender, never()).sendRelativeInvitation(anyString(), anyString(), anyString(), anyString(),
                anyString());

        // Validé : l'email part avec le lien
        call(julien, HttpMethod.POST, family.relatives() + "/" + relativeId + "/approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVITED"))
                .andExpect(jsonPath("$.expired").value(false));
        call(julien, HttpMethod.GET, family.path() + "/to-do", null)
                .andExpect(jsonPath("$.mine", hasSize(0)));
        String token = sentToken(odile.email());

        // Aperçu public, puis Odile rejoint
        mvc.perform(get("/api/v1/relative-invitations/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Mamie Odile"))
                .andExpect(jsonPath("$.parentFirstNames[0]").value("Camille"))
                .andExpect(jsonPath("$.parentFirstNames[1]").value("Julien"))
                .andExpect(jsonPath("$.childFirstNames[0]").value("Léa"));
        call(odile, HttpMethod.POST, "/api/v1/relative-invitations/" + token + "/accept", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.familyId").value(family.id()))
                .andExpect(jsonPath("$.relation").value("GRANDPARENT"))
                .andExpect(jsonPath("$.children[0].firstName").value("Léa"));
        call(odile, HttpMethod.POST, "/api/v1/relative-invitations/" + token + "/accept", null)
                .andExpect(status().isNotFound());
        call(odile, HttpMethod.GET, "/api/v1/users/me/relative-families", null)
                .andExpect(jsonPath("$", hasSize(1)));
        call(camille, HttpMethod.GET, family.relatives(), null)
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].email").doesNotExist());

        // Cercles : « Partagé » par défaut, « Famille » au choix
        String shared = JsonPath.read(publish(camille, family, "Entre nous", null)
                .andExpect(jsonPath("$.circle").value("PARENTS"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String forFamily = JsonPath.read(publish(camille, family, "Pour la famille", "FAMILY")
                .andExpect(jsonPath("$.circle").value("FAMILY"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        call(odile, HttpMethod.GET, family.posts(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts", hasSize(1)))
                .andExpect(jsonPath("$.posts[0].id").value(forFamily))
                .andExpect(jsonPath("$.posts[0].canDelete").value(false))
                .andExpect(jsonPath("$.posts[0].canRequestRemoval").value(false));
        for (String path : List.of("/" + shared, "/" + shared + "/comments")) {
            call(odile, HttpMethod.GET, family.posts() + path, null).andExpect(status().isNotFound());
        }
        call(odile, HttpMethod.PUT, family.posts() + "/" + shared + "/reactions/HEART", null)
                .andExpect(status().isNotFound());

        // Rien d'autre de la famille
        for (String path : List.of("/to-do", "/relatives", "/documents", "/expenses", "/conversations",
                "/children/" + family.childId() + "/profile")) {
            call(odile, HttpMethod.GET, family.path() + path, null).andExpect(status().isNotFound());
        }

        // Elle réagit, commente et publie (toujours « Famille »)
        call(odile, HttpMethod.PUT, family.posts() + "/" + forFamily + "/reactions/HEART", null)
                .andExpect(jsonPath("$.reactions[0].count").value(1))
                .andExpect(jsonPath("$.reactions[0].mine").value(true));
        call(camille, HttpMethod.PUT, family.posts() + "/" + forFamily + "/reactions/HEART", null)
                .andExpect(jsonPath("$.reactions[0].count").value(2));
        call(odile, HttpMethod.POST, family.posts() + "/" + forFamily + "/comments", "{\"body\":\"Bravo Léa !\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstName").value("Mamie Odile"))
                .andExpect(jsonPath("$.relation").value("GRANDPARENT"));
        String hers = JsonPath.read(publish(odile, family, "Au parc avec Léa", "PARENTS")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.circle").value("FAMILY"))
                .andExpect(jsonPath("$.authorRelation").value("GRANDPARENT"))
                .andExpect(jsonPath("$.authorFirstName").value("Mamie Odile"))
                .andExpect(jsonPath("$.canDelete").value(true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        call(odile, HttpMethod.POST, family.posts() + "/" + forFamily + "/removal", null)
                .andExpect(status().isConflict());

        // Un parent demande le retrait de sa publication : masquée, elle seule la voit encore
        call(julien, HttpMethod.GET, family.posts() + "/" + hers, null)
                .andExpect(jsonPath("$.canRequestRemoval").value(true));
        call(julien, HttpMethod.POST, family.posts() + "/" + hers + "/removal", "{\"reason\":\"PLACE\"}")
                .andExpect(status().isOk());
        call(odile, HttpMethod.GET, family.posts() + "/" + hers, null)
                .andExpect(jsonPath("$.removal.requestedByRole").value("PARENT_B"))
                .andExpect(jsonPath("$.removal.mine").value(false));
        call(odile, HttpMethod.POST, family.posts() + "/" + hers + "/removal/keep", null)
                .andExpect(status().isOk());

        // Camille la retire : accès coupé tout de suite
        call(camille, HttpMethod.DELETE, family.relatives() + "/" + relativeId, null)
                .andExpect(status().isNoContent());
        call(odile, HttpMethod.GET, family.posts(), null).andExpect(status().isNotFound());
        call(odile, HttpMethod.GET, "/api/v1/users/me/relative-families", null)
                .andExpect(jsonPath("$", hasSize(0)));
        call(camille, HttpMethod.GET, family.relatives(), null).andExpect(jsonPath("$", hasSize(0)));
        // Ses publications restent, avec son prénom
        call(camille, HttpMethod.GET, family.posts() + "/" + hers, null)
                .andExpect(jsonPath("$.authorFirstName").value("Mamie Odile"));
    }

    @Test
    void inSoloTheProposalWaitsForTheSecondParent() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Family family = family(camille);

        String relativeId = JsonPath.read(propose(camille, family, "Sophie", "sophie" + TEST_DOMAIN, "STEP_PARENT")
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.canApprove").value(false))
                .andReturn().getResponse().getContentAsString(), "$.id");
        call(camille, HttpMethod.POST, family.relatives() + "/" + relativeId + "/approve", null)
                .andExpect(status().isConflict());

        // Julien arrive : la proposition lui est demandée, il refuse
        join(camille, julien, family);
        call(julien, HttpMethod.GET, family.path() + "/to-do", null)
                .andExpect(jsonPath("$.mine[?(@.kind == 'MEMBER')]", hasSize(1)));
        call(julien, HttpMethod.POST, family.relatives() + "/" + relativeId + "/decline", null)
                .andExpect(status().isNoContent());
        call(camille, HttpMethod.GET, family.relatives(), null).andExpect(jsonPath("$", hasSize(0)));
        call(julien, HttpMethod.GET, family.path() + "/to-do", null)
                .andExpect(jsonPath("$.mine[?(@.kind == 'MEMBER')]", hasSize(0)));
        verify(emailSender, never()).sendRelativeInvitation(anyString(), anyString(), anyString(), anyString(),
                anyString());
    }

    @Test
    void refusalsResendAndLeaving() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Account gerard = verifiedUser("Gérard");
        Account stranger = verifiedUser("Paul");
        Family family = family(camille);
        join(camille, julien, family);
        Family other = family(stranger);

        propose(camille, family, "Julien", julien.email(), "UNCLE_AUNT").andExpect(status().isBadRequest());
        propose(camille, family, "Gérard", "", "GRANDPARENT").andExpect(status().isBadRequest());
        String relativeId = JsonPath.read(propose(camille, family, "Gérard", gerard.email().toUpperCase(),
                "GRANDPARENT").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        propose(julien, family, "Papi", gerard.email(), "GRANDPARENT").andExpect(status().isConflict());
        call(stranger, HttpMethod.GET, family.relatives(), null).andExpect(status().isNotFound());
        call(stranger, HttpMethod.POST, other.relatives() + "/" + relativeId + "/approve", null)
                .andExpect(status().isNotFound());

        call(julien, HttpMethod.POST, family.relatives() + "/" + relativeId + "/approve", null)
                .andExpect(status().isOk());
        String first = sentToken(gerard.email());

        // Un nouveau lien remplace le précédent
        clearInvocations(emailSender);
        call(camille, HttpMethod.POST, family.relatives() + "/" + relativeId + "/resend", null)
                .andExpect(status().isOk());
        String second = sentToken(gerard.email());
        mvc.perform(get("/api/v1/relative-invitations/" + first)).andExpect(status().isNotFound());

        // Un compte de parent ne rejoint pas en tant que proche
        call(stranger, HttpMethod.POST, "/api/v1/relative-invitations/" + second + "/accept", null)
                .andExpect(status().isConflict());

        call(gerard, HttpMethod.POST, "/api/v1/relative-invitations/" + second + "/accept", null)
                .andExpect(status().isOk());
        call(gerard, HttpMethod.GET, family.posts(), null).andExpect(status().isOk());
        call(gerard, HttpMethod.DELETE, "/api/v1/users/me/relative-families/" + family.id(), null)
                .andExpect(status().isNoContent());
        call(gerard, HttpMethod.GET, family.posts(), null).andExpect(status().isNotFound());
        call(gerard, HttpMethod.DELETE, "/api/v1/users/me/relative-families/" + family.id(), null)
                .andExpect(status().isNotFound());
    }

    @Test
    void aRelativesDataExportHoldsTheirFamiliesPostsCommentsAndReactions() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Account odile = verifiedUser("Odile");
        Family family = family(camille);
        join(camille, julien, family);
        String relativeId = JsonPath.read(propose(camille, family, "Mamie Odile", odile.email(), "GRANDPARENT")
                .andReturn().getResponse().getContentAsString(), "$.id");
        call(julien, HttpMethod.POST, family.relatives() + "/" + relativeId + "/approve", null)
                .andExpect(status().isOk());
        call(odile, HttpMethod.POST, "/api/v1/relative-invitations/" + sentToken(odile.email()) + "/accept", null)
                .andExpect(status().isOk());

        String camillePost = JsonPath.read(publish(camille, family, "Léa à vélo", "FAMILY")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        publish(camille, family, "Entre parents", "PARENTS").andExpect(status().isCreated());
        String odilePost = JsonPath.read(publish(odile, family, "Bravo ma chérie", null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        call(odile, HttpMethod.POST, family.posts() + "/" + camillePost + "/comments", "{\"body\":\"Magnifique\"}")
                .andExpect(status().isCreated());
        call(odile, HttpMethod.PUT, family.posts() + "/" + camillePost + "/reactions/HEART", null)
                .andExpect(status().isOk());
        call(camille, HttpMethod.POST, family.posts() + "/" + odilePost + "/comments", "{\"body\":\"Merci\"}")
                .andExpect(status().isCreated());
        // Album : un proche ne voit que les photos du cercle « Famille »
        byte[] photo = com.vynk.coparent_backend.common.storage.PhotoCleanerTest.jpeg(
                new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB));
        for (String circle : List.of("FAMILY", "PARENTS")) {
            mvc.perform(multipart(family.posts())
                            .file(new org.springframework.mock.web.MockMultipartFile("photos", "p.jpg", "image/jpeg",
                                    photo))
                            .param("childIds", family.childId()).param("circle", circle)
                            .header("Authorization", "Bearer " + camille.accessToken()))
                    .andExpect(status().isCreated());
        }
        call(odile, HttpMethod.GET, family.path() + "/album?childId=" + family.childId(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos", hasSize(1)));
        call(julien, HttpMethod.GET, family.path() + "/album?childId=" + family.childId(), null)
                .andExpect(jsonPath("$.photos", hasSize(2)));

        // Quitter la famille ne retire pas ses publications de son export
        call(odile, HttpMethod.DELETE, "/api/v1/users/me/relative-families/" + family.id(), null)
                .andExpect(status().isNoContent());

        call(odile, HttpMethod.POST, "/api/v1/users/me/data-export", null).andExpect(status().isAccepted());
        ArgumentCaptor<byte[]> json = ArgumentCaptor.forClass(byte[].class);
        verify(emailSender).sendDataExport(any(User.class), anyString(), json.capture());
        String export = new String(json.getValue(), StandardCharsets.UTF_8);

        assertThat((Object) JsonPath.read(export, "$.family")).isNull();
        assertThat((String) JsonPath.read(export, "$.relativeFamilies[0].familyName")).isEqualTo("Famille de Léa");
        assertThat((String) JsonPath.read(export, "$.relativeFamilies[0].relation")).isEqualTo("GRANDPARENT");
        assertThat((String) JsonPath.read(export, "$.relativeFamilies[0].status")).isEqualTo("LEFT");
        List<String> bodies = JsonPath.read(export, "$.relativeFamilies[0].posts[*].body");
        assertThat(bodies).containsExactly("Bravo ma chérie");
        assertThat((String) JsonPath.read(export, "$.relativeFamilies[0].posts[0].circle")).isEqualTo("FAMILY");
        assertThat((String) JsonPath.read(export, "$.relativeFamilies[0].posts[0].comments[0].firstName"))
                .isEqualTo("Camille");
        assertThat((String) JsonPath.read(export, "$.relativeFamilies[0].comments[0].body")).isEqualTo("Magnifique");
        assertThat((String) JsonPath.read(export, "$.relativeFamilies[0].comments[0].postId")).isEqualTo(camillePost);
        assertThat((String) JsonPath.read(export, "$.relativeFamilies[0].reactions[0].kind")).isEqualTo("HEART");
        assertThat(export).doesNotContain("Entre parents").doesNotContain("Léa à vélo");
    }

    private ResultActions propose(Account parent, Family family, String firstName, String email, String relation)
            throws Exception {
        return call(parent, HttpMethod.POST, family.relatives(), "{\"firstName\":\"" + firstName + "\",\"email\":\""
                + email + "\",\"relation\":\"" + relation + "\"}");
    }

    /** Le jeton du dernier email d'invitation envoyé à cette adresse. */
    private String sentToken(String email) {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendRelativeInvitation(eq(email.toLowerCase()), anyString(), anyString(), anyString(),
                link.capture());
        String value = link.getValue();
        return value.substring(value.indexOf("token=") + "token=".length());
    }

    private ResultActions publish(Account account, Family family, String text, String circle) throws Exception {
        var builder = multipart(family.posts()).param("body", text);
        if (circle != null) {
            builder.param("circle", circle);
        }
        builder.characterEncoding(StandardCharsets.UTF_8);
        return mvc.perform(builder.header("Authorization", "Bearer " + account.accessToken()));
    }

    private Account verifiedUser(String firstName) throws Exception {
        String email = "user-" + UUID.randomUUID() + TEST_DOMAIN;
        String body = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"confirmPassword\":\""
                                + PASSWORD + "\",\"firstName\":\"" + firstName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE users SET email_verified_at = UTC_TIMESTAMP(6) WHERE email = ?", email);
        return new Account(JsonPath.read(body, "$.user.id"), email, JsonPath.read(body, "$.accessToken"));
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
        String invitation = call(inviter, HttpMethod.POST, family.path() + "/invitations",
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
