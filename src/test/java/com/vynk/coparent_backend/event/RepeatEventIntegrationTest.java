package com.vynk.coparent_backend.event;

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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Événements répétés contre la base MySQL locale. Une semaine sur deux à partir du lundi S (dans une à deux
 * semaines) : Camille (A) a les nuits S..S+6, Julien (B) S+7..S+13, etc. Les séances de test sont des mardis,
 * hors vacances (filtre désactivé), sauf le test des vacances, qui lit des dates fixes.
 */
@SpringBootTest(properties = {
        "app.auth.check-compromised-passwords=false",
        "app.auth.jwt-secret=test-only-secret-that-is-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class RepeatEventIntegrationTest {

    private static final String TEST_DOMAIN = "@test.coparent.invalid";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String FAMILY = "{\"name\":\"Famille de Léa et Hugo\",\"vacationZone\":\"A\"}";
    private static final LocalDate S = LocalDate.now(ZoneId.of("Europe/Paris")).plusDays(7)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    private static final LocalDate TUESDAY = S.plusDays(1);

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

    private record Family(String id, String hugo, String events) {
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
    void weeklyOnlyWhenTheChildIsWithJulienFollowsCustody() throws Exception {
        Account camille = verifiedUser("Camille");
        Family family = family(camille);

        call(camille, HttpMethod.POST, family.events(), event("Judo", family.hugo(), TUESDAY,
                repeat("WEEKLY", "\"PARENT_B\"", false, "\"COUNT\"", "3")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repeat.frequency").value("WEEKLY"))
                .andExpect(jsonPath("$.repeat.onlyWith").value("PARENT_B"))
                .andExpect(jsonPath("$.repeat.firstDate").value(TUESDAY.toString()));

        // Mardis chez Julien : S+8, S+22, S+36 (3 séances), pas S+1, S+15, S+29
        assertThat(dates(camille, family, TUESDAY, TUESDAY.plusDays(70)))
                .containsExactly(TUESDAY.plusWeeks(1), TUESDAY.plusWeeks(3), TUESDAY.plusWeeks(5));
    }

    @Test
    void editAndDeleteOneFollowingOrAllSessions() throws Exception {
        Account camille = verifiedUser("Camille");
        Account julien = verifiedUser("Julien");
        Family family = family(camille);
        join(camille, julien, family);
        String id = id(call(camille, HttpMethod.POST, family.events(), event("Judo", family.hugo(), TUESDAY,
                repeat("WEEKLY", "null", false, "\"NEVER\"", "null"))).andExpect(status().isCreated()));
        String series = family.events() + "/" + id;

        // Une séance seulement : elle quitte la série et devient un événement unique (déplacé au mercredi)
        LocalDate second = TUESDAY.plusWeeks(1);
        String moved = id(call(julien, HttpMethod.PUT, series + "?scope=ONE&date=" + second,
                event("Judo (décalé)", family.hugo(), second.plusDays(1), "null"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repeat").doesNotExist()));
        assertThat(moved).isNotEqualTo(id);
        assertThat(dates(camille, family, TUESDAY, TUESDAY.plusWeeks(3)))
                .containsExactly(TUESDAY, second.plusDays(1), TUESDAY.plusWeeks(2), TUESDAY.plusWeeks(3));

        // Celui-ci et les suivants : la série s'arrête la veille, une nouvelle commence
        LocalDate third = TUESDAY.plusWeeks(2);
        String next = id(call(camille, HttpMethod.PUT, series + "?scope=FOLLOWING&date=" + third,
                event("Judo (nouvel horaire)", family.hugo(), third,
                        repeat("WEEKLY", "null", false, "\"NEVER\"", "null")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Judo (nouvel horaire)"))
                .andExpect(jsonPath("$.repeat.firstDate").value(third.toString())));
        call(camille, HttpMethod.GET, series + "?date=" + TUESDAY, null)
                .andExpect(jsonPath("$.repeat.until").value(third.minusDays(1).toString()));
        call(camille, HttpMethod.GET, family.events() + "?from=" + third + "&to=" + third, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(next));

        // Supprimer cette séance seulement, puis les suivantes
        call(camille, HttpMethod.DELETE, family.events() + "/" + next + "?scope=ONE&date=" + third.plusWeeks(1),
                null).andExpect(status().isNoContent());
        call(camille, HttpMethod.DELETE, family.events() + "/" + next + "?scope=FOLLOWING&date="
                + third.plusWeeks(2), null).andExpect(status().isNoContent());
        assertThat(dates(camille, family, third, third.plusWeeks(6))).containsExactly(third);

        // Toute la série : sans séance passée, toute la série change sur place
        call(camille, HttpMethod.PUT, series + "?scope=ALL", event("Judo du mardi", family.hugo(), TUESDAY,
                repeat("WEEKLY", "null", false, "\"NEVER\"", "null")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        // Supprimer toute la série d'origine
        call(camille, HttpMethod.DELETE, series + "?scope=ALL", null).andExpect(status().isNoContent());
        call(camille, HttpMethod.GET, series, null).andExpect(status().isNotFound());
    }

    @Test
    void errorsOnSeries() throws Exception {
        Account camille = verifiedUser("Camille");
        Family family = family(camille);
        String series = family.events() + "/" + id(call(camille, HttpMethod.POST, family.events(),
                event("Judo", family.hugo(), TUESDAY, repeat("WEEKLY", "null", false, "\"NEVER\"", "null")))
                .andExpect(status().isCreated()));

        call(camille, HttpMethod.DELETE, series, null).andExpect(status().isBadRequest());
        call(camille, HttpMethod.DELETE, series + "?scope=ONE&date=" + TUESDAY.plusDays(1), null)
                .andExpect(status().isNotFound());
        call(camille, HttpMethod.POST, family.events(), event("Judo", family.hugo(), TUESDAY,
                repeat("WEEKLY", "null", false, "\"COUNT\"", "null"))).andExpect(status().isBadRequest());
        call(camille, HttpMethod.POST, family.events(), event("Judo", family.hugo(), TUESDAY,
                repeat("WEEKLY", "null", false, "\"NEVER\"", "5"))).andExpect(status().isBadRequest());
    }

    @Test
    void noSessionDuringSchoolHolidaysAndEndOfSchoolYear() throws Exception {
        Account camille = verifiedUser("Camille");
        Family family = family(camille);
        // Zone A : Noël du 19 décembre 2026 au 4 janvier 2027 ; été 2027 à partir du 3 juillet
        call(camille, HttpMethod.POST, family.events(), event("Piscine", family.hugo(), LocalDate.of(2026, 12, 15),
                repeat("WEEKLY", "null", true, "\"SCHOOL_YEAR\"", "null")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repeat.until").value("2027-07-02"));

        assertThat(dates(camille, family, LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 15)))
                .containsExactly(LocalDate.of(2026, 12, 15), LocalDate.of(2027, 1, 5), LocalDate.of(2027, 1, 12));
        assertThat(dates(camille, family, LocalDate.of(2027, 6, 20), LocalDate.of(2027, 7, 31)))
                .containsExactly(LocalDate.of(2027, 6, 22), LocalDate.of(2027, 6, 29));
    }

    @Test
    void previewShowsTheNextDatesAndWhyTheyAreSkipped() throws Exception {
        Account camille = verifiedUser("Camille");
        Family family = family(camille);
        call(camille, HttpMethod.POST, family.events() + "/repeat-preview", "{\"date\":\"" + TUESDAY
                + "\",\"repeat\":" + repeat("WEEKLY", "\"PARENT_B\"", false, "\"COUNT\"", "2") + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].date").value(TUESDAY.toString()))
                .andExpect(jsonPath("$[0].parent").value("PARENT_A"))
                .andExpect(jsonPath("$[0].skipped").value("OTHER_PARENT"))
                .andExpect(jsonPath("$[1].parent").value("PARENT_B"))
                .andExpect(jsonPath("$[1].skipped").doesNotExist())
                .andExpect(jsonPath("$[3].date").value(TUESDAY.plusWeeks(3).toString()));
    }

    private List<LocalDate> dates(Account account, Family family, LocalDate from, LocalDate to) throws Exception {
        String body = call(account, HttpMethod.GET, family.events() + "?from=" + from + "&to=" + to, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> dates = JsonPath.read(body, "$[*].date");
        return dates.stream().map(LocalDate::parse).toList();
    }

    private static String repeat(String frequency, String onlyWith, boolean skipHolidays, String end,
                                 String count) {
        return "{\"frequency\":\"" + frequency + "\",\"onlyWith\":" + onlyWith + ",\"skipHolidays\":"
                + skipHolidays + ",\"end\":" + end + ",\"count\":" + count + "}";
    }

    private static String event(String title, String childId, LocalDate date, String repeat) {
        return "{\"title\":\"" + title + "\",\"type\":\"ACTIVITY\",\"childIds\":[\"" + childId + "\"],"
                + "\"date\":\"" + date + "\",\"allDay\":false,\"startTime\":\"17:00\",\"endTime\":\"18:00\","
                + "\"reminder\":\"NONE\",\"visibility\":\"SHARED\",\"repeat\":" + repeat + "}";
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
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

    /** Famille de Camille (A) avec Hugo, une semaine sur deux à partir de S, sans règle de vacances. */
    private Family family(Account account) throws Exception {
        String familyId = id(call(account, HttpMethod.POST, "/api/v1/families", FAMILY)
                .andExpect(status().isCreated()));
        String hugo = id(call(account, HttpMethod.POST, "/api/v1/families/" + familyId + "/children",
                "{\"firstName\":\"Hugo\",\"birthDate\":\"2019-03-03\"}").andExpect(status().isCreated()));
        call(account, HttpMethod.PUT, "/api/v1/families/" + familyId + "/custody-schedule",
                "{\"pattern\":\"ALTERNATE_WEEKS\",\"startDate\":\"" + S
                        + "\",\"handoverTime\":\"17:50\",\"startingParent\":\"PARENT_A\"}")
                .andExpect(status().isOk());
        return new Family(familyId, hugo, "/api/v1/families/" + familyId + "/events");
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
