package org.lime.chatbotwithai.conversation;

import org.lime.chatbotwithai.ai.QueryFilter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ConversationSession {

    private final String id = UUID.randomUUID().toString();
    private final Map<SlotType, SlotStage> slotStages = new EnumMap<>(SlotType.class);
    private final List<String> userUtterances = new ArrayList<>();
    private final ConversationMetrics metrics = new ConversationMetrics();
    private final boolean capacityRefineExperiment;
    private final boolean askDimensionsExperiment;

    private QueryFilter filter = new QueryFilter();
    private boolean completed;
    private String localeHint = "auto";

    public ConversationSession(boolean capacityRefineExperiment, boolean askDimensionsExperiment) {
        this.capacityRefineExperiment = capacityRefineExperiment;
        this.askDimensionsExperiment = askDimensionsExperiment;
        for (SlotType slot : SlotType.values()) {
            slotStages.put(slot, SlotStage.MISSING);
        }
    }

    public String getId() {
        return id;
    }

    public Map<SlotType, SlotStage> getSlotStages() {
        return slotStages;
    }

    public ConversationMetrics getMetrics() {
        return metrics;
    }

    public QueryFilter getFilter() {
        return filter;
    }

    public void setFilter(QueryFilter filter) {
        this.filter = filter;
    }

    public List<String> getUserUtterances() {
        return userUtterances;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public boolean isCapacityRefineExperiment() {
        return capacityRefineExperiment;
    }

    public boolean isAskDimensionsExperiment() {
        return askDimensionsExperiment;
    }

    public String getLocaleHint() {
        return localeHint;
    }

    public void setLocaleHint(String localeHint) {
        this.localeHint = localeHint;
    }
}

