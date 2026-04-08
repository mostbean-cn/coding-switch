package com.github.mostbean.codingswitch.service;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * GeminiAuthSupport 的单元测试。
 */
public class GeminiAuthSupportTest {

    @Test
    public void shouldDetectEmptyAuthAsNone() {
        assertEquals(GeminiAuthSupport.GeminiAuthState.NONE, GeminiAuthSupport.detectState(""));
        assertEquals(GeminiAuthSupport.GeminiAuthState.NONE, GeminiAuthSupport.detectState("   "));
        assertEquals(GeminiAuthSupport.GeminiAuthState.NONE, GeminiAuthSupport.detectState(null));
    }

    @Test
    public void shouldDetectInvalidJsonAsNone() {
        assertEquals(GeminiAuthSupport.GeminiAuthState.NONE, GeminiAuthSupport.detectState("not a json"));
        assertEquals(GeminiAuthSupport.GeminiAuthState.NONE, GeminiAuthSupport.detectState("{invalid}"));
    }

    @Test
    public void shouldDetectValidOAuthAsOfficialLogin() {
        String validOAuth = """
                {
                    "access_token": "ya29.a0AfH6SMA...",
                    "refresh_token": "1//0abc...",
                    "token_type": "Bearer",
                    "expiry_date": 1700000000000
                }
                """;
        assertEquals(GeminiAuthSupport.GeminiAuthState.OFFICIAL_LOGIN, GeminiAuthSupport.detectState(validOAuth));
        assertTrue(GeminiAuthSupport.isValidOfficialLoginAuth(validOAuth));
    }

    @Test
    public void shouldDetectOAuthWithExpiresInAsOfficialLogin() {
        String validOAuth = """
                {
                    "access_token": "ya29.a0AfH6SMA...",
                    "refresh_token": "1//0abc...",
                    "token_type": "Bearer",
                    "expires_in": 3599
                }
                """;
        assertEquals(GeminiAuthSupport.GeminiAuthState.OFFICIAL_LOGIN, GeminiAuthSupport.detectState(validOAuth));
    }

    @Test
    public void shouldRejectMissingAccessToken() {
        String invalidOAuth = """
                {
                    "refresh_token": "1//0abc...",
                    "token_type": "Bearer",
                    "expiry_date": 1700000000000
                }
                """;
        assertEquals(GeminiAuthSupport.GeminiAuthState.NONE, GeminiAuthSupport.detectState(invalidOAuth));
        assertFalse(GeminiAuthSupport.isValidOfficialLoginAuth(invalidOAuth));
    }

    @Test
    public void shouldRejectMissingRefreshToken() {
        String invalidOAuth = """
                {
                    "access_token": "ya29.a0AfH6SMA...",
                    "token_type": "Bearer",
                    "expiry_date": 1700000000000
                }
                """;
        assertEquals(GeminiAuthSupport.GeminiAuthState.NONE, GeminiAuthSupport.detectState(invalidOAuth));
    }

    @Test
    public void shouldRejectMissingTokenType() {
        String invalidOAuth = """
                {
                    "access_token": "ya29.a0AfH6SMA...",
                    "refresh_token": "1//0abc...",
                    "expiry_date": 1700000000000
                }
                """;
        assertEquals(GeminiAuthSupport.GeminiAuthState.NONE, GeminiAuthSupport.detectState(invalidOAuth));
    }

    @Test
    public void shouldRejectMissingExpiry() {
        String invalidOAuth = """
                {
                    "access_token": "ya29.a0AfH6SMA...",
                    "refresh_token": "1//0abc...",
                    "token_type": "Bearer"
                }
                """;
        assertEquals(GeminiAuthSupport.GeminiAuthState.NONE, GeminiAuthSupport.detectState(invalidOAuth));
    }

    @Test
    public void shouldDetectApiKeyMode() {
        String envContent = """
                GEMINI_API_KEY=AIzaSy...
                GEMINI_MODEL=gemini-pro
                """;
        assertTrue(GeminiAuthSupport.isApiKeyMode(envContent));
    }

    @Test
    public void shouldNotDetectApiKeyModeWithoutApiKey() {
        String envContent = """
                GEMINI_MODEL=gemini-pro
                GOOGLE_GEMINI_BASE_URL=https://example.com
                """;
        assertFalse(GeminiAuthSupport.isApiKeyMode(envContent));
    }

    @Test
    public void shouldNotDetectApiKeyModeForEmptyContent() {
        assertFalse(GeminiAuthSupport.isApiKeyMode(""));
        assertFalse(GeminiAuthSupport.isApiKeyMode("   "));
        assertFalse(GeminiAuthSupport.isApiKeyMode(null));
    }

    @Test
    public void shouldIgnoreCommentsInApiKeyMode() {
        String envContent = """
                # This is a comment
                # GEMINI_API_KEY=should_not_count
                GEMINI_MODEL=gemini-pro
                """;
        assertFalse(GeminiAuthSupport.isApiKeyMode(envContent));
    }

    @Test
    public void shouldRejectInvalidJsonForOfficialLoginAuth() {
        assertFalse(GeminiAuthSupport.isValidOfficialLoginAuth(""));
        assertFalse(GeminiAuthSupport.isValidOfficialLoginAuth("not json"));
        assertFalse(GeminiAuthSupport.isValidOfficialLoginAuth("{}"));
    }

    @Test
    public void shouldValidateGoogleAccountsState() {
        String validAccounts = """
                {
                    "active": "user@example.com",
                    "old": ["old@example.com"]
                }
                """;
        assertTrue(GeminiAuthSupport.isValidGoogleAccountsState(validAccounts));
    }

    @Test
    public void shouldRejectInvalidGoogleAccountsState() {
        String invalidAccounts = """
                {
                    "active": {},
                    "old": ["old@example.com", ""]
                }
                """;
        assertFalse(GeminiAuthSupport.isValidGoogleAccountsState(invalidAccounts));
        assertFalse(GeminiAuthSupport.isValidGoogleAccountsState("not json"));
        assertFalse(GeminiAuthSupport.isValidGoogleAccountsState(""));
    }
}
