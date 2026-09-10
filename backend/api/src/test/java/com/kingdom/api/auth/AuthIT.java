package com.kingdom.api.auth;

import com.kingdom.api.support.AbstractPostgresIT;
import com.kingdom.api.support.TestAuthSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
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
    void registerLoginAndMeFlow() throws Exception {
        String registerBody = """
                {
                  "username": "bob",
                  "email": "bob@test.com",
                  "password": "password123"
                }
                """;

        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token", notNullValue()))
                .andExpect(jsonPath("$.userId", notNullValue()))
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.email").value("bob@test.com"))
                .andReturn();

        String token = TestAuthSupport.extractJsonField(registerResult.getResponse().getContentAsString(), "token");
        assertThat(token).isNotBlank();

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.email").value("bob@test.com"))
                .andExpect(jsonPath("$.rating").value(1200));
    }

    @Test
    void registerLoginGetTokenFlow() throws Exception {
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
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.username").value("grace"))
                .andExpect(jsonPath("$.expiresIn").exists())
                .andReturn();

        String token = TestAuthSupport.extractJsonField(loginResult.getResponse().getContentAsString(), "token");
        assertThat(token).isNotBlank();

        mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("grace"))
                .andExpect(jsonPath("$.email").value("grace@test.com"));
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
        mockMvc.perform(get("/api/me"))  // 401
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.requestId", not(emptyOrNullString())));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        String registerBody = """
                {
                  "username": "carol",
                  "email": "carol@test.com",
                  "password": "password123"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated());

        String loginBody = """
                {
                  "email": "carol@test.com",
                  "password": "wrong-password"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void authResponsesDoNotContainPasswordFields() throws Exception {
        String registerBody = """
                {
                  "username": "dave",
                  "email": "dave@test.com",
                  "password": "password123"
                }
                """;

        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andReturn();
        assertNoPasswordFields(registerResult.getResponse().getContentAsString());

        String loginBody = """
                {
                  "email": "dave@test.com",
                  "password": "password123"
                }
                """;

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();
        assertNoPasswordFields(loginResult.getResponse().getContentAsString());

        String token = TestAuthSupport.extractJsonField(loginResult.getResponse().getContentAsString(), "token");
        MvcResult meResult = mockMvc.perform(get("/api/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertNoPasswordFields(meResult.getResponse().getContentAsString());
    }

    @Test
    void registerJsonMatchesContractKeys() throws Exception {
        String registerBody = """
                {
                  "username": "erin",
                  "email": "erin@test.com",
                  "password": "password123"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.username").exists())
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.expiresIn").doesNotExist());
    }

    @Test
    void loginJsonMatchesContractKeys() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "frank",
                                  "email": "frank@test.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "frank@test.com",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.username").exists())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.expiresIn").exists())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
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

    private static void assertNoPasswordFields(String body) {
        assertThat(body).doesNotContain("\"password\"");
        assertThat(body).doesNotContain("\"passwordHash\"");
        assertThat(body).doesNotContain("\"hash\"");
    }
}
