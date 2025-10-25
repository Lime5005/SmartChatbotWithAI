package org.lime.chatbotwithai.conversation;

import org.lime.chatbotwithai.ai.QueryFilter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.lime.chatbotwithai.conversation.ConversationTurnResponse.AssistantMessage;

@Service
public class QuestionGenerationService {

    private final ChatClient chatClient;

    private static final PromptTemplate QUESTION_TEMPLATE = new PromptTemplate("""
        You write the next assistant message in a guided washing-machine shopping conversation.

        Language: {language}
        SlotGoal: {slot_description}
        Stage: {stage}
        KnownFilters:
        {filters}

        PreviewHighlights:
        {preview}

        LatestUserMessage:
        {latest_user_message}

        ContextHint:
        {context_hint}

        Guidelines:
        - Friendly, curious, and helpful — sound human, not robotic.
        - Keep responses under 40 words unless giving product details.
        - Begin each message by naturally acknowledging what the user said (“Sure, I see you’re looking for…” / “Got it, you prefer…”).
        - Show you remember confirmed preferences by weaving relevant details from KnownFilters into your reply when it helps the flow.
        - Do not invent product names. Only reference products from PreviewHighlights; if none are available, explain you'll look for matching options without naming them.
        - Keep on asking questions until enough info is gathered to recommend a product.
        - Search or match the closest products.
        - If no exact match: suggest 3 alternatives (same type, different brand/price/model).
        - Keep the flow natural: “That one’s currently unavailable, but I can suggest a similar option from [brand].”
        - Once enough info is gathered, recommend the best product and explain why, and give 1-2 alternatives.
        - Use product details from the preview when relevant (brand, model, price, type, capacity, badges).
        - Ask one clear question at a time to gather missing info.
        - When the user confirms they want to order (“yes,” “that’s the one,” “I’ll take it”), acknowledge warmly and close the session.
        - Avoid bullet points, emojis, or mentioning “slot”, “stage”, or UI chips.

        Respond with the message text only.
        """);

    private static final PromptTemplate COMPLETION_TEMPLATE = new PromptTemplate("""
        You are about to reveal the final shortlist of washing machines.

        Language: {language}
        PreviewExamples:
        {preview}

        Write one short sentence that:
        - Celebrates the progress so far.
        - Invites the user to review the shortlist.
        - Stays under 25 words.
        - Avoids mentions of “slot”, “stage”, or UI mechanics.

        Return only the sentence.
        """);

    public QuestionGenerationService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public AssistantMessage generateQuestion(SlotType slot,
                                             SlotStage stage,
                                             QueryFilter currentFilter,
                                             List<String> previewHighlights,
                                             String localeHint,
                                             String latestUserMessage,
                                             String contextHint) {
        String language = resolveLanguage(localeHint);
        String filters = renderFilterSummary(currentFilter);
        String preview = previewHighlights == null || previewHighlights.isEmpty()
                ? "none"
                : previewHighlights.stream().limit(3).collect(Collectors.joining("\n"));
        String lastMessage = StringUtils.hasText(latestUserMessage) ? latestUserMessage : "none";
        String context = StringUtils.hasText(contextHint) ? contextHint : "none";
        Map<String, Object> vars = Map.of(
                "language", language,
                "slot_description", slotDescription(slot),
                "stage", stage.name().toLowerCase(Locale.ROOT),
                "filters", filters,
                "preview", preview,
                "latest_user_message", lastMessage,
                "context_hint", context
        );
        String message = chatClient.prompt(QUESTION_TEMPLATE.create(vars))
                .call()
                .content()
                .trim();
        return new AssistantMessage(message, hintForSlot(slot, language));
    }

    public AssistantMessage generateCompletion(List<String> previewHighlights, String localeHint) {
        String language = resolveLanguage(localeHint);
        String preview = previewHighlights == null || previewHighlights.isEmpty()
                ? "none"
                : previewHighlights.stream().limit(3).collect(Collectors.joining("\n"));
        Map<String, Object> vars = Map.of(
                "language", language,
                "preview", preview
        );
        String message = chatClient.prompt(COMPLETION_TEMPLATE.create(vars))
                .call()
                .content()
                .trim();
        return new AssistantMessage(message, null);
    }

    private static String resolveLanguage(String hint) {
        return Locale.ENGLISH.getLanguage();
    }

    private static String slotDescription(SlotType slot) {
        return switch (slot) {
            case BUDGET -> "customer's comfortable price range in euros";
            case TYPE -> "preferred loading style: front-load or top-load";
            case CAPACITY -> "desired drum capacity in kilograms";
            case BRAND -> "brand preferences or exclusions";
            case DIMENSIONS -> "width, height, depth constraints in centimetres";
        };
    }

    private static String renderFilterSummary(QueryFilter filter) {
        if (filter == null) {
            return "Budget: unknown\nType: unknown\nCapacity: unknown\nBrand: none\nDimensions: unknown";
        }
        return """
            Budget: %s
            Type: %s
            Capacity: %s
            Brand: %s
            Dimensions: %s
            """.formatted(
                safeString(describeBudget(filter)),
                safeString(filter.getType()),
                safeString(describeCapacity(filter)),
                filter.isBrandFlexible() ? "any brand" : safeString(filter.getBrand()),
                safeString(describeDimensions(filter))
        );
    }

    private static String describeBudget(QueryFilter filter) {
        if (filter.getMinPrice() == null && filter.getMaxPrice() == null) {
            return null;
        }
        if (filter.getMinPrice() != null && filter.getMaxPrice() != null) {
            return "€%d-€%d".formatted(filter.getMinPrice().intValue(), filter.getMaxPrice().intValue());
        }
        if (filter.getMaxPrice() != null) {
            return "≤ €%d".formatted(filter.getMaxPrice().intValue());
        }
        return "≥ €%d".formatted(filter.getMinPrice().intValue());
    }

    private static String describeCapacity(QueryFilter filter) {
        if (filter.getMinCapacityKg() == null && filter.getMaxCapacityKg() == null) {
            return null;
        }
        if (filter.getMinCapacityKg() != null && filter.getMaxCapacityKg() != null) {
            return "%d-%dkg".formatted(filter.getMinCapacityKg(), filter.getMaxCapacityKg());
        }
        if (filter.getMaxCapacityKg() != null) {
            return "≤ %dkg".formatted(filter.getMaxCapacityKg());
        }
        return "≥ %dkg".formatted(filter.getMinCapacityKg());
    }

    private static String describeDimensions(QueryFilter filter) {
        if (filter.getWidthCm() == null && filter.getHeightCm() == null && filter.getDepthCm() == null) {
            return null;
        }
        String w = filter.getWidthCm() == null ? "?" : String.format("%.0fcm", filter.getWidthCm());
        String h = filter.getHeightCm() == null ? "?" : String.format("%.0fcm", filter.getHeightCm());
        String d = filter.getDepthCm() == null ? "?" : String.format("%.0fcm", filter.getDepthCm());
        return "%s × %s × %s".formatted(w, h, d);
    }

    private static String safeString(String value) {
        return value == null ? "unknown" : value;
    }

    private static String hintForSlot(SlotType slot, String language) {
        return switch (slot) {
            case BUDGET -> "Tap a quick chip for common budgets.";
            case TYPE -> "Chips cover front vs top load.";
            case CAPACITY -> "Popular kg sizes sit on the chips.";
            case BRAND -> "Brand chips are ready if you have a favorite.";
            case DIMENSIONS -> "Try typing 60×85×55 cm if you know it.";
        };
    }
}
