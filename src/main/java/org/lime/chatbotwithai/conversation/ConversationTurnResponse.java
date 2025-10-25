package org.lime.chatbotwithai.conversation;

import lombok.Builder;
import lombok.Singular;

import java.util.List;
import java.util.Map;

@Builder
public record ConversationTurnResponse(
        String sessionId,
        String status,
        AssistantMessage assistant,
        @Singular("chip") List<String> chips,
        PreviewBlock preview,
        ResultBlock result,
        List<SlotSnapshot> slots,
        Map<String, Object> metrics
) {

    public record AssistantMessage(String text, String hint) {
    }

    public record PreviewBlock(String headline, List<PreviewItem> items) {
    }

    public record PreviewItem(Long id, String brand, String model, Double price, String type, Integer capacityKg, List<String> badges) {
    }

    public record ResultBlock(String explanation, List<PreviewItem> items) {
    }

    public record SlotSnapshot(String slot, String stage, String value) {
    }
}

