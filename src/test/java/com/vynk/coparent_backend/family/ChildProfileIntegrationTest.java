package com.vynk.coparent_backend.family;

import com.jayway.jsonpath.JsonPath;
import com.vynk.coparent_backend.auth.email.EmailSender;
import com.vynk.coparent_backend.auth.service.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fiche de l'enfant (école, santé, contacts), contre la base MySQL locale. Camille est A, Julien B. */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class ChildProfileIntegrationTest {

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

    private record Account(String id, String accessToken) {
    }

    private record Family(String id, String childId) {
        String profile() {
            return "/api/v1/families/" + id + "/children/" + childId + "/profile";
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
    void bothParentsReadAndEditTheSheetWithTheLastChangeShown() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Family family = family(camille);
        join(camille, julien, family);

        // Jamais remplie : vide, sans trace
        call(julien, HttpMethod.GET, family.profile(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childId").value(family.childId()))
                .andExpect(jsonPath("$.school", nullValue()))
                .andExpect(jsonPath("$.contacts", hasSize(0)))
                .andExpect(jsonPath("$.updatedAt", nullValue()));

        call(camille, HttpMethod.PUT, family.profile(), """
                {"school":"  École élémentaire Jean-Jaurès ","grade":"CE2","teacher":"Mme Lefèvre",
                 "allergies":"Arachides. Trousse d'urgence dans le cartable.","doctor":"Dr Martin",
                 "doctorPhone":"01 23 45 67 89",
                 "contacts":[{"name":"Inès","role":"Nounou, mercredi après-midi","phone":"+33 6 12 34 56 78"},
                             {"name":"Accueil périscolaire","role":"","phone":null}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school").value("École élémentaire Jean-Jaurès"))
                .andExpect(jsonPath("$.contacts[1].role", nullValue()))
                .andExpect(jsonPath("$.updatedByRole").value("PARENT_A"))
                .andExpect(jsonPath("$.updatedByFirstName").value("Camille"));

        call(julien, HttpMethod.GET, family.profile(), null)
                .andExpect(jsonPath("$.grade").value("CE2"))
                .andExpect(jsonPath("$.allergies").value("Arachides. Trousse d'urgence dans le cartable."))
                .andExpect(jsonPath("$.doctorPhone").value("01 23 45 67 89"))
                .andExpect(jsonPath("$.contacts", hasSize(2)))
                .andExpect(jsonPath("$.contacts[0].name").value("Inès"))
                .andExpect(jsonPath("$.contacts[0].phone").value("+33 6 12 34 56 78"))
                .andExpect(jsonPath("$.contacts[1].name").value("Accueil périscolaire"))
                .andExpect(jsonPath("$.updatedAt").exists());

        // Julien remplace la fiche : champ vidé, contacts remplacés, trace à son nom
        call(julien, HttpMethod.PUT, family.profile(), """
                {"school":"École élémentaire Jean-Jaurès","grade":"CM1","teacher":"",
                 "contacts":[{"name":"Club de judo","role":"Mardi, 16 h 30","phone":"0145678901"}]}""")
                .andExpect(status().isOk());
        call(camille, HttpMethod.GET, family.profile(), null)
                .andExpect(jsonPath("$.grade").value("CM1"))
                .andExpect(jsonPath("$.teacher", nullValue()))
                .andExpect(jsonPath("$.allergies", nullValue()))
                .andExpect(jsonPath("$.contacts", hasSize(1)))
                .andExpect(jsonPath("$.contacts[0].name").value("Club de judo"))
                .andExpect(jsonPath("$.updatedByRole").value("PARENT_B"))
                .andExpect(jsonPath("$.updatedByFirstName").value("Julien"));

        // Les données de santé ne passent pas par la famille
        call(julien, HttpMethod.GET, "/api/v1/families/me", null)
                .andExpect(jsonPath("$.children[0].allergies").doesNotExist());
    }

    @Test
    void worksInSoloAndRefusesInvalidSheets() throws Exception {
        Account camille = verifiedUser("Camille");
        Family family = family(camille);

        call(camille, HttpMethod.PUT, family.profile(), "{\"doctor\":\"Dr Martin\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctor").value("Dr Martin"))
                .andExpect(jsonPath("$.contacts", hasSize(0)));

        call(camille, HttpMethod.PUT, family.profile(), "{\"doctorPhone\":\"appelle-moi\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Le numéro du médecin n'est pas valide."));
        call(camille, HttpMethod.PUT, family.profile(),
                "{\"contacts\":[{\"name\":\"Inès\",\"phone\":\"12\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Le numéro de Inès n'est pas valide."));
        call(camille, HttpMethod.PUT, family.profile(), "{\"contacts\":[{\"name\":\"  \"}]}")
                .andExpect(status().isBadRequest());
        String eleven = IntStream.range(0, 11).mapToObj(i -> "{\"name\":\"Contact " + i + "\"}")
                .collect(Collectors.joining(","));
        call(camille, HttpMethod.PUT, family.profile(), "{\"contacts\":[" + eleven + "]}")
                .andExpect(status().isBadRequest());
        call(camille, HttpMethod.PUT, family.profile(), "{\"allergies\":\"" + "a".repeat(501) + "\"}")
                .andExpect(status().isBadRequest());

        // Refusé, rien n'a changé
        call(camille, HttpMethod.GET, family.profile(), null)
                .andExpect(jsonPath("$.doctor").value("Dr Martin"))
                .andExpect(jsonPath("$.doctorPhone", nullValue()));
    }

    @Test
    void anotherFamilyCannotReadOrEditTheSheet() throws Exception {
        Account camille = verifiedUser("Camille");
        Account stranger = verifiedUser("Paul");
        Family family = family(camille);
        Family other = family(stranger);

        call(stranger, HttpMethod.GET, family.profile(), null).andExpect(status().isNotFound());
        call(stranger, HttpMethod.PUT, family.profile(), "{\"doctor\":\"Dr X\"}").andExpect(status().isNotFound());
        // Un enfant d'une autre famille, sous sa propre famille : introuvable
        call(camille, HttpMethod.GET, "/api/v1/families/" + family.id() + "/children/" + other.childId()
                + "/profile", null).andExpect(status().isNotFound());
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
