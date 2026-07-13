package com.github.mostbean.codingswitch.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodexModelCatalogSupportTest {

    @Test
    public void buildsCatalogWithMultipleModels() {
        JsonObject catalog = CodexModelCatalogSupport.buildCatalog(List.of(
                new CodexModelCatalogSupport.ModelDefinition("GPT 5.4", "gpt-5.4", 1_000_000L),
                new CodexModelCatalogSupport.ModelDefinition("MiniMax M2.5", "MiniMax-M2.5", 204_800L)
        ));

        JsonArray models = catalog.getAsJsonArray("models");
        assertEquals(2, models.size());

        JsonObject first = models.get(0).getAsJsonObject();
        assertEquals("GPT 5.4", first.get("display_name").getAsString());
        assertEquals("gpt-5.4", first.get("slug").getAsString());
        assertEquals(1_000_000L, first.get("context_window").getAsLong());
        assertEquals(1_000_000L, first.get("max_context_window").getAsLong());
        assertEquals("list", first.get("visibility").getAsString());
        assertFalse(first.get("base_instructions").getAsString().isBlank());
    }

    @Test
    public void readsEditableFieldsFromCatalog() {
        JsonObject catalog = CodexModelCatalogSupport.buildCatalog(List.of(
                new CodexModelCatalogSupport.ModelDefinition("模型 A", "model-a", 128_000L),
                new CodexModelCatalogSupport.ModelDefinition("模型 B", "model-b", 64_000L)
        ));

        List<CodexModelCatalogSupport.ModelDefinition> definitions =
                CodexModelCatalogSupport.readDefinitions(catalog);

        assertEquals(2, definitions.size());
        assertEquals("模型 B", definitions.get(1).displayName());
        assertEquals("model-b", definitions.get(1).model());
        assertEquals(64_000L, definitions.get(1).contextWindow());
    }

    @Test
    public void replacesCatalogPathWithCurrentAbsolutePath() {
        String toml = "model = \"model-a\"\nmodel_catalog_json = \"D:/old/catalog.json\"\n";

        String updated = CodexModelCatalogSupport.ensureCatalogPath(
                toml,
                Path.of("D:/users/test/.codex/coding-switch-model-catalog.json"));

        assertTrue(updated.contains(
                "model_catalog_json = \"D:/users/test/.codex/coding-switch-model-catalog.json\""));
        assertFalse(updated.contains("D:/old/catalog.json"));
    }
}
