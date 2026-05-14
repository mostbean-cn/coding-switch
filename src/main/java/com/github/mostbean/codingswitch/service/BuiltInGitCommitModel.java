package com.github.mostbean.codingswitch.service;

import com.github.mostbean.codingswitch.model.AiModelFormat;
import com.github.mostbean.codingswitch.model.AiModelProfile;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class BuiltInGitCommitModel {

    private static final String BASE_URL = "https://openrouter.ai/api/v1";
    private static final String MODEL = "openrouter/free";
    private static final String KEY = "pQbJJdH/fLK2JyhfTSopVQdeDwQdiMYPm5b6r09nSrA=";
    private static final String IV = "D/YaYQsvCQbwU5Ods+396w==";
    private static final String CIPHER_TEXT =
        "pGXYh0aHtumkcRvZYX07QbNleAu+80qAMg4rd6qUnXKwrx7feE4EiVzgSinIWrj/"
            + "ZV7QsDmGzOHWLHGkhT4C17Zv3yh8EMl7LN9bqstLDdo=";

    private BuiltInGitCommitModel() {
    }

    static AiModelProfile profile() {
        AiModelProfile profile = new AiModelProfile();
        profile.setName("Built-in Git Commit Model");
        profile.setFormat(AiModelFormat.OPENAI_CHAT_COMPLETIONS);
        profile.setBaseUrl(BASE_URL);
        profile.setModel(MODEL);
        profile.setTimeoutSeconds(30);
        return profile;
    }

    static String apiKey() {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(Base64.getDecoder().decode(KEY), "AES"),
                new IvParameterSpec(Base64.getDecoder().decode(IV))
            );
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(CIPHER_TEXT));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Built-in Git commit model key is invalid", ex);
        }
    }
}
