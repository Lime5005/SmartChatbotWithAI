package org.lime.chatbotwithai.conversation;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class ConversationMetrics {

    private int turnCount;
    private int slotsCompleted;
    private int previewsTriggered;
    private int previewWithHits;
    private int finalRetrievals;
    private int finalRetrievalWithHits;
    private int addToCartClicks;
    private final Instant createdAt = Instant.now();

    public void incrementTurn() {
        turnCount++;
    }

    public void incrementSlotsCompleted() {
        slotsCompleted++;
    }

    public void previewTriggered(boolean hadHits) {
        previewsTriggered++;
        if (hadHits) {
            previewWithHits++;
        }
    }

    public void finalRetrieval(boolean hadHits) {
        finalRetrievals++;
        if (hadHits) {
            finalRetrievalWithHits++;
        }
    }

    public void addToCartClick() {
        addToCartClicks++;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("turnCount", turnCount);
        payload.put("slotsCompleted", slotsCompleted);
        payload.put("previewsTriggered", previewsTriggered);
        payload.put("previewHitRate", previewsTriggered == 0 ? 0d : previewWithHits * 1.0 / previewsTriggered);
        payload.put("finalRetrievals", finalRetrievals);
        payload.put("finalRetrievalHitRate", finalRetrievals == 0 ? 0d : finalRetrievalWithHits * 1.0 / finalRetrievals);
        payload.put("addToCartClicks", addToCartClicks);
        payload.put("conversationAgeSeconds", Math.max(0, Instant.now().getEpochSecond() - createdAt.getEpochSecond()));
        return payload;
    }
}

