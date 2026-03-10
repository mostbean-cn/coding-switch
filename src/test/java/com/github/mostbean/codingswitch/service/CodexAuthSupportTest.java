package com.github.mostbean.codingswitch.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodexAuthSupportTest {

    @Test
    public void shouldDetectEmptyAuthAsNone() {
        assertEquals(CodexAuthSupport.CodexAuthState.NONE, CodexAuthSupport.detectState(""));
    }

    @Test
    public void shouldDetectApiKeyAuth() {
        String raw = """
                {
                  "OPENAI_API_KEY": "sk-test"
                }
                """;
        assertEquals(CodexAuthSupport.CodexAuthState.API_KEY, CodexAuthSupport.detectState(raw));
        assertFalse(CodexAuthSupport.isValidOfficialLoginAuth(raw));
    }

    @Test
    public void shouldDetectOfficialLoginAuth() {
        String raw = """
                {
                  "auth_mode": "chatgpt",
                  "last_refresh": "2026-03-10T05:17:40.891951400Z",
                  "tokens": {
                    "id_token": "id-token",
                    "access_token": "access-token",
                    "refresh_token": "refresh-token"
                  }
                }
                """;
        assertEquals(CodexAuthSupport.CodexAuthState.OFFICIAL_LOGIN, CodexAuthSupport.detectState(raw));
        assertTrue(CodexAuthSupport.isValidOfficialLoginAuth(raw));
    }

    @Test
    public void shouldRejectBrokenOfficialLoginAuth() {
        String raw = """
                {
                  "auth_mode": "chatgpt",
                  "tokens": {
                    "id_token": "id-token",
                    "access_token": "",
                    "refresh_token": "refresh-token"
                  }
                }
                """;
        assertEquals(CodexAuthSupport.CodexAuthState.NONE, CodexAuthSupport.detectState(raw));
        assertFalse(CodexAuthSupport.isValidOfficialLoginAuth(raw));
    }

    @Test
    public void shouldRejectInvalidJson() {
        assertFalse(CodexAuthSupport.isValidOfficialLoginAuth("{invalid"));
        assertEquals(CodexAuthSupport.CodexAuthState.NONE, CodexAuthSupport.detectState("{invalid"));
    }
}
