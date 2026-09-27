package com.kingdom.api.auth;

import com.kingdom.api.support.AbstractPostgresIT;
import com.kingdom.api.support.TestAuthSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerIssuesToken_matchesContract_andAuthorizesMe() throws Exception {
        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "bob",
                                  "email": "bob@test.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId", notNullValue()))
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.email").value("bob@test.com"))
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.expiresIn").doesNotExist())
                .andReturn();

        String body = registerResult.getResponse().getContentAsString();
        assertNoPasswordFields(body);
        String token = TestAuthSupport.extractJsonField(body, "token");
        assertThat(token).isNotBlank();

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.email").value("bob@test.com"))
                .andExpect(jsonPath("$.rating").value(1200))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void loginIssuesToken_matchesContract_andAuthorizesMe() throws Exception {
        TestAuthSupport.register(mockMvc, "grace", "grace@test.com");

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "grace@test.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.username").value("grace"))
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.expiresIn").exists())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        String body = loginResult.getResponse().getContentAsString();
        assertNoPasswordFields(body);
        String token = TestAuthSupport.extractJsonField(body, "token");
        assertThat(token).isNotBlank();

        ResultActions me = mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("grace"))
                .andExpect(jsonPath("$.email").value("grace@test.com"))
                .andExpect(jsonPath("$.password").doesNotExist());
        assertNoPasswordFields(me.andReturn().getResponse().getContentAsString());
    }

    @Test
    void meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void validationErrorIncludesRequestId() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Request-Id", "req_test_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\",\"email\":\"bad\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").value("req_test_1"))
                .andExpect(header().string("X-Request-Id", "req_test_1"));
    }

    @Test
    void generatesRequestIdWhenMissing() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.requestId", not(emptyOrNullString())));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "carol",
                                  "email": "carol@test.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "carol@test.com",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void registerDuplicateEmailReturns409() throws Exception {
        TestAuthSupport.register(mockMvc, "alice", "a@test.com");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "alice2",
                                  "email": "a@test.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void registerDuplicateUsernameReturns409() throws Exception {
        TestAuthSupport.register(mockMvc, "alice", "a1@test.com");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "alice",
                                  "email": "a2@test.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void malformedRegisterBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void registerOversizedEmailReturns400() throws Exception {
        String oversizedEmail = "a".repeat(92) + "@test.com";
        assertThat(oversizedEmail).hasSize(101);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "toolong",
                                  "email": "%s",
                                  "password": "password123"
                                }
                                """.formatted(oversizedEmail)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void registerPasswordAt72AsciiBytesSucceeds() throws Exception {
        String password = "a".repeat(72);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "bcryptok",
                                  "email": "bcryptok@test.com",
                                  "password": "%s"
                                }
                                """.formatted(password)))
                .andExpect(status().isCreated());
    }

    @Test
    void registerPasswordOver72AsciiBytesReturns400() throws Exception {
        String password = "a".repeat(73);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "bcryptlong",
                                  "email": "bcryptlong@test.com",
                                  "password": "%s"
                                }
                                """.formatted(password)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("72 UTF-8 bytes")));
    }

    @Test
    void registerMultibytePasswordOver72BytesReturns400() throws Exception {
        // 37 × é = 74 UTF-8 bytes, only 37 characters — character @Size(max=72) would miss this
        String password = "é".repeat(37);
        assertThat(password.length()).isEqualTo(37);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "unicodelong",
                                  "email": "unicodelong@test.com",
                                  "password": "%s"
                                }
                                """.formatted(password)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("72 UTF-8 bytes")));
    }

    @Test
    void loginPasswordOver72BytesReturns400() throws Exception {
        TestAuthSupport.register(mockMvc, "loginlong", "loginlong@test.com");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "loginlong@test.com",
                                  "password": "%s"
                                }
                                """.formatted("a".repeat(73))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("72 UTF-8 bytes")));
    }

    private static void assertNoPasswordFields(String body) {
        assertThat(body).doesNotContain("\"password\"");
        assertThat(body).doesNotContain("\"passwordHash\"");
        assertThat(body).doesNotContain("\"hash\"");
    }
}
