package com.github.mostbean.codingswitch.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PiAuthSupportTest {

    @Test
    public void shouldDetectEmptyAuthAsNone() {
        assertEquals(PiAuthSupport.PiAuthState.NONE, PiAuthSupport.detectState(""));
        assertFalse(PiAuthSupport.isValidOfficialLoginAuth(""));
    }

    @Test
    public void shouldDetectApiKeyAsNone() {
        String raw = """
                {
                  "deepseek": { "type": "api_key", "key": "sk-test" }
                }
                """;
        assertEquals(PiAuthSupport.PiAuthState.NONE, PiAuthSupport.detectState(raw));
        assertFalse(PiAuthSupport.isValidOfficialLoginAuth(raw));
    }

    @Test
    public void shouldDetectOauthAsOfficialLogin() {
        String raw = """
                {
                  "anthropic": {
                    "type": "oauth",
                    "access": "access-token",
                    "refresh": "refresh-token",
                    "expires": 1710000000000
                  }
                }
                """;
        assertEquals(PiAuthSupport.PiAuthState.OFFICIAL_LOGIN, PiAuthSupport.detectState(raw));
        assertTrue(PiAuthSupport.isValidOfficialLoginAuth(raw));
    }

    @Test
    public void shouldRejectBrokenOauth() {
        String raw = """
                {
                  "anthropic": {
                    "type": "oauth",
                    "access": "",
                    "refresh": ""
                  }
                }
                """;
        assertEquals(PiAuthSupport.PiAuthState.NONE, PiAuthSupport.detectState(raw));
        assertFalse(PiAuthSupport.isValidOfficialLoginAuth(raw));
    }

    @Test
    public void shouldRejectInvalidJson() {
        assertFalse(PiAuthSupport.isValidOfficialLoginAuth("{invalid"));
        assertEquals(PiAuthSupport.PiAuthState.NONE, PiAuthSupport.detectState("{invalid"));
    }
}
