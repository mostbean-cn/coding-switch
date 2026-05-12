package com.github.mostbean.codingswitch.completion;

import com.github.mostbean.codingswitch.model.AiCompletionTriggerMode;
import com.github.mostbean.codingswitch.service.AiCompletionService;
import com.github.mostbean.codingswitch.service.AiCompletionTriggerState;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.util.ProcessingContext;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

public class AiCompletionContributor extends CompletionContributor {

    public AiCompletionContributor() {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            new CompletionProvider<>() {
                @Override
                protected void addCompletions(
                    @NotNull CompletionParameters parameters,
                    @NotNull ProcessingContext context,
                    @NotNull CompletionResultSet result
                ) {
                    addAiCompletion(parameters, result);
                }
            }
        );
    }

    private void addAiCompletion(CompletionParameters parameters, CompletionResultSet result) {
        boolean manual = AiCompletionTriggerState.consumeManual(parameters.getEditor());
        AiCompletionTriggerMode mode = manual ? AiCompletionTriggerMode.MANUAL : AiCompletionTriggerMode.AUTO;
        try {
            Optional<String> completion = AiCompletionService.getInstance()
                .complete(parameters.getOriginalFile().getProject(), parameters.getEditor(), mode);
            completion
                .map(this::createLookupElement)
                .ifPresent(result.withPrefixMatcher("")::addElement);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 补全请求失败时保持安静，避免自动触发场景频繁打扰用户。
        }
    }

    private LookupElement createLookupElement(String completion) {
        String display = completion.lines()
            .findFirst()
            .orElse(completion)
            .trim();
        if (display.length() > 80) {
            display = display.substring(0, 77) + "...";
        }
        LookupElementBuilder element = LookupElementBuilder.create(completion)
            .withPresentableText(display.isBlank() ? "AI completion" : display)
            .withTypeText("Coding Switch AI", true)
            .withInsertHandler((context, item) -> context.setAddCompletionChar(false));
        return PrioritizedLookupElement.withPriority(element, 1000.0);
    }
}
