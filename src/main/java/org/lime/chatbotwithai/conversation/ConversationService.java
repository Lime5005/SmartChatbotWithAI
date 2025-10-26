package org.lime.chatbotwithai.conversation;

import org.lime.chatbotwithai.ai.QueryExtractionService;
import org.lime.chatbotwithai.ai.QueryFilter;
import org.lime.chatbotwithai.ai.SearchAnswerService;
import org.lime.chatbotwithai.product.BrandCatalog;
import org.lime.chatbotwithai.product.Product;
import org.lime.chatbotwithai.product.ProductSearchService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.lime.chatbotwithai.conversation.ConversationTurnResponse.*;

@Service
public class ConversationService {

    private static final double DIMENSION_TOLERANCE_CM = 1.0;
    private static final int PREVIEW_LIMIT = 3;
    private static final int FINAL_LIMIT = 5;
    private static final Pattern QUOTED_SELECTION = Pattern.compile("\"([^\"]+)\"");

    private final QueryExtractionService extractor;
    private final ProductSearchService productSearchService;
    private final BrandCatalog brandCatalog;
    private final QuestionGenerationService questionGenerationService;
    private final SearchAnswerService answerService;
    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private static final String[] PURCHASE_KEY_PHRASES = {
            "i'll take", "i will take", "lets take", "let's take", "take the", "take that one",
            "that's the one", "that's it", "i'll go with", "i will go with", "go with that",
            "i'm taking", "i want the", "we'll take", "we will take", "ok we'll take", "order that",
            "buy that", "i'll choose", "consider it done", "lock it in",
            "我要这个", "我要這個", "就这个", "就這個", "买这个", "買這個", "就它了", "就它吧", "就这个吧",
            "好的就它", "就选这个", "我要那台", "就决定这个"
    };

    public ConversationService(QueryExtractionService extractor,
                               ProductSearchService productSearchService,
                               BrandCatalog brandCatalog,
                               QuestionGenerationService questionGenerationService,
                               SearchAnswerService answerService) {
        this.extractor = extractor;
        this.productSearchService = productSearchService;
        this.brandCatalog = brandCatalog;
        this.questionGenerationService = questionGenerationService;
        this.answerService = answerService;
    }

    public ConversationTurnResponse startConversation(String localeHint) {
        ConversationSession session = new ConversationSession(
                false,
                true
        );
        if (StringUtils.hasText(localeHint)) {
            session.setLocaleHint(localeHint);
        }
        sessions.put(session.getId(), session);
        SlotType nextSlot = SlotType.BUDGET;
        AssistantMessage question = questionGenerationService.generateQuestion(
                nextSlot,
                SlotStage.MISSING,
                session.getFilter(),
                List.of(),
                session.getLocaleHint(),
                null,
                null
        );
        return ConversationTurnResponse.builder()
                .sessionId(session.getId())
                .status("collecting")
                .assistant(question)
                .chips(chipsFor(nextSlot))
                .slots(buildSlotSnapshots(session))
                .metrics(session.getMetrics().snapshot())
                .build();
    }

    public ConversationTurnResponse applyUserReply(String sessionId, UserReplyRequest request) {
        ConversationSession session = requireSession(sessionId);
        String userText = resolveUserText(request);
        if (!StringUtils.hasText(userText)) {
            return ConversationTurnResponse.builder()
                    .sessionId(session.getId())
                    .status(session.isCompleted() ? "completed" : "collecting")
                    .assistant(new AssistantMessage("I did not catch that — could you rephrase?", null))
                    .chips(List.of())
                    .slots(buildSlotSnapshots(session))
                    .metrics(session.getMetrics().snapshot())
                    .build();
        }

        session.getMetrics().incrementTurn();
        session.getUserUtterances().add(userText);
        detectLocale(session, userText);

        QueryFilter previousFilter = copyFilter(session.getFilter());
        QueryFilter merged = mergeFilters(session, extractor.extract(String.join("\n", session.getUserUtterances())));
        session.setFilter(merged);
        updateSlotStages(session);
        boolean brandRelaxed = previousFilter != null
                && previousFilter.getBrand() != null
                && merged.getBrand() == null;
        String contextHint = brandRelaxed ? "brand_relaxed:" + previousFilter.getBrand() : null;

        List<Product> preview = producePreviewIfUseful(session);
        session.getMetrics().previewTriggered(!preview.isEmpty());

        String selectionHint = extractSelection(userText, session.getFilter(), preview, null);

        if (isPurchaseIntent(userText, selectionHint)) {
            session.setCompleted(true);
            List<Product> results = productSearchService.finalResults(
                    String.join(". ", session.getUserUtterances()),
                    session.getFilter(),
                    FINAL_LIMIT,
                    DIMENSION_TOLERANCE_CM
            );
            session.getMetrics().finalRetrieval(!results.isEmpty());
            String selection = selectionHint != null
                    ? selectionHint
                    : extractSelection(userText, session.getFilter(), preview, results);
            ResultBlock resultBlock = results.isEmpty() ? null : new ResultBlock(
                    answerService.explain(
                            String.join(". ", session.getUserUtterances()),
                            session.getFilter(),
                            results
                    ),
                    toPreviewItems(results, session.getFilter())
            );
            AssistantMessage closing = new AssistantMessage(
                    buildPurchaseClosing(selection, session.getLocaleHint()),
                    null
            );
            return ConversationTurnResponse.builder()
                    .sessionId(session.getId())
                    .status("completed")
                    .assistant(closing)
                    .result(resultBlock)
                    .preview(previewBlock(preview, session))
                    .slots(buildSlotSnapshots(session))
                    .metrics(session.getMetrics().snapshot())
                    .build();
        }

        if (shouldFinalize(session)) {
            session.setCompleted(true);
            List<Product> results = productSearchService.finalResults(
                    String.join(". ", session.getUserUtterances()),
                    session.getFilter(),
                    FINAL_LIMIT,
                    DIMENSION_TOLERANCE_CM
            );
            session.getMetrics().finalRetrieval(!results.isEmpty());
            ResultBlock resultBlock = new ResultBlock(
                    answerService.explain(
                            String.join(". ", session.getUserUtterances()),
                            session.getFilter(),
                            results
                    ),
                    toPreviewItems(results, session.getFilter())
            );
            return ConversationTurnResponse.builder()
                    .sessionId(session.getId())
                    .status("completed")
                    .assistant(questionGenerationService.generateCompletion(
                            previewHighlights(results),
                            session.getLocaleHint()
                    ))
                    .result(resultBlock)
                    .preview(previewBlock(preview, session))
                    .slots(buildSlotSnapshots(session))
                    .metrics(session.getMetrics().snapshot())
                    .build();
        }

        SlotType nextSlot = determineNextSlot(session);
        AssistantMessage assistantMessage = questionGenerationService.generateQuestion(
                nextSlot,
                session.getSlotStages().getOrDefault(nextSlot, SlotStage.MISSING),
                session.getFilter(),
                previewHighlights(preview),
                session.getLocaleHint(),
                userText,
                contextHint
        );

        return ConversationTurnResponse.builder()
                .sessionId(session.getId())
                .status("collecting")
                .assistant(assistantMessage)
                .chips(chipsFor(nextSlot))
                .preview(previewBlock(preview, session))
                .slots(buildSlotSnapshots(session))
                .metrics(session.getMetrics().snapshot())
                .build();
    }

    private static QueryFilter copyFilter(QueryFilter original) {
        if (original == null) {
            return null;
        }
        QueryFilter copy = new QueryFilter();
        copy.setBrand(original.getBrand());
        copy.setType(original.getType());
        copy.setMinPrice(original.getMinPrice());
        copy.setMaxPrice(original.getMaxPrice());
        copy.setMinCapacityKg(original.getMinCapacityKg());
        copy.setMaxCapacityKg(original.getMaxCapacityKg());
        copy.setWidthCm(original.getWidthCm());
        copy.setHeightCm(original.getHeightCm());
        copy.setDepthCm(original.getDepthCm());
        copy.setBrandFlexible(original.isBrandFlexible());
        return copy;
    }

    public ConversationTurnResponse recordEvent(String sessionId, ConversationEventRequest event) {
        ConversationSession session = requireSession(sessionId);
        if (event != null && "add_to_cart".equalsIgnoreCase(event.type())) {
            session.getMetrics().addToCartClick();
        }
        return ConversationTurnResponse.builder()
                .sessionId(session.getId())
                .status(session.isCompleted() ? "completed" : "collecting")
                .assistant(new AssistantMessage("Noted ✅", null))
                .slots(buildSlotSnapshots(session))
                .metrics(session.getMetrics().snapshot())
                .build();
    }

    private ConversationSession requireSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
    }

    private static String resolveUserText(UserReplyRequest request) {
        if (request == null) {
            return null;
        }
        if (StringUtils.hasText(request.message())) {
            return request.message().trim();
        }
        if (StringUtils.hasText(request.chip())) {
            return request.chip().trim();
        }
        return null;
    }

    private static void detectLocale(ConversationSession session, String userText) {
        session.setLocaleHint("en");
    }

    private QueryFilter mergeFilters(ConversationSession session, QueryFilter incoming) {
        QueryFilter baseline = Optional.ofNullable(session.getFilter()).orElseGet(QueryFilter::new);
        if (incoming == null) {
            return baseline;
        }
        QueryFilter merged = new QueryFilter();
        String incomingBrand = incoming.getBrand();
        boolean incomingFlexible = incoming.isBrandFlexible();
        if (incomingFlexible) {
            merged.setBrand(null);
        } else if (incomingBrand != null) {
            merged.setBrand(incomingBrand);
        } else if (baseline.isBrandFlexible()) {
            merged.setBrand(null);
        } else {
            merged.setBrand(baseline.getBrand());
        }
        merged.setType(firstNonNull(incoming.getType(), baseline.getType()));
        Double candidateMinPrice = validPrice(incoming.getMinPrice());
        Double candidateMaxPrice = validPrice(incoming.getMaxPrice());
        Double baselineMinPrice = validPrice(baseline.getMinPrice());
        Double baselineMaxPrice = validPrice(baseline.getMaxPrice());
        Double mergedMinPrice = firstNonNull(candidateMinPrice, baselineMinPrice);
        Double mergedMaxPrice = firstNonNull(candidateMaxPrice, baselineMaxPrice);
        if (mergedMinPrice != null && mergedMaxPrice != null && mergedMinPrice > mergedMaxPrice) {
            mergedMinPrice = null;
        }
        merged.setMinPrice(mergedMinPrice);
        merged.setMaxPrice(mergedMaxPrice);
        merged.setMinCapacityKg(firstNonNull(incoming.getMinCapacityKg(), baseline.getMinCapacityKg()));
        merged.setMaxCapacityKg(firstNonNull(incoming.getMaxCapacityKg(), baseline.getMaxCapacityKg()));
        merged.setWidthCm(firstNonNull(incoming.getWidthCm(), baseline.getWidthCm()));
        merged.setHeightCm(firstNonNull(incoming.getHeightCm(), baseline.getHeightCm()));
        merged.setDepthCm(firstNonNull(incoming.getDepthCm(), baseline.getDepthCm()));
        boolean brandFlexible = incomingFlexible || (incomingBrand == null && baseline.isBrandFlexible());
        merged.setBrandFlexible(brandFlexible);
        return merged;
    }

    private static <T> T firstNonNull(T candidate, T fallback) {
        return candidate != null ? candidate : fallback;
    }

    private static boolean hasAtLeastOneValue(QueryFilter filter) {
        return filter.getBrand() != null || filter.getType() != null
                || filter.getMinPrice() != null || filter.getMaxPrice() != null
                || filter.getMinCapacityKg() != null || filter.getMaxCapacityKg() != null
                || filter.getWidthCm() != null || filter.getHeightCm() != null || filter.getDepthCm() != null;
    }

    private List<Product> producePreviewIfUseful(ConversationSession session) {
        if (!hasAtLeastOneValue(session.getFilter())) {
            return List.of();
        }
        return productSearchService.preview(session.getFilter(), PREVIEW_LIMIT);
    }

    private PreviewBlock previewBlock(List<Product> preview, ConversationSession session) {
        if (preview == null || preview.isEmpty()) {
            return null;
        }
        String headline = "Preview with current filters";
        return new PreviewBlock(headline, toPreviewItems(preview, session.getFilter()));
    }

    private static List<PreviewItem> toPreviewItems(List<Product> products, QueryFilter filter) {
        if (products == null) {
            return List.of();
        }
        return products.stream()
                .map(product -> new PreviewItem(
                        product.getId(),
                        product.getBrand(),
                        product.getModel(),
                        product.getPrice(),
                        product.getType(),
                        product.getCapacityKg(),
                        deriveBadges(product, filter)
                ))
                .toList();
    }

    private static List<String> deriveBadges(Product product, QueryFilter filter) {
        List<String> badges = new ArrayList<>();
        if (filter != null) {
            if (filter.getMaxPrice() != null && product.getPrice() != null && product.getPrice() <= filter.getMaxPrice()) {
                badges.add("Within budget");
            }
            if (filter.getMinCapacityKg() != null && product.getCapacityKg() != null && product.getCapacityKg() >= filter.getMinCapacityKg()) {
                badges.add("Capacity match");
            }
            if (filter.getType() != null && Objects.equals(filter.getType().toLowerCase(), String.valueOf(product.getType()).toLowerCase())) {
                badges.add("Type match");
            }
            if (hasDimensionConstraints(filter) && dimensionsMatch(filter, product)) {
                badges.add("Dimension fit");
            }
            if (filter.getBrand() != null && product.getBrand() != null && filter.getBrand().equalsIgnoreCase(product.getBrand())) {
                badges.add("Brand match");
            }
        }
        return badges;
    }

    private static boolean hasDimensionConstraints(QueryFilter filter) {
        return filter.getWidthCm() != null || filter.getHeightCm() != null || filter.getDepthCm() != null;
    }

    private static boolean dimensionsMatch(QueryFilter filter, Product product) {
        return matchesDimension(filter.getWidthCm(), product.getWidthCm())
                && matchesDimension(filter.getHeightCm(), product.getHeightCm())
                && matchesDimension(filter.getDepthCm(), product.getDepthCm());
    }

    private static boolean matchesDimension(Double expected, Double actual) {
        if (expected == null) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        return Math.abs(actual - expected) <= DIMENSION_TOLERANCE_CM;
    }

    private void updateSlotStages(ConversationSession session) {
        QueryFilter filter = session.getFilter();
        Map<SlotType, SlotStage> updated = new EnumMap<>(SlotType.class);
        updated.put(SlotType.BUDGET, evaluateBudgetStage(filter));
        updated.put(SlotType.TYPE, filter.getType() == null ? SlotStage.MISSING : SlotStage.REFINED);
        updated.put(SlotType.CAPACITY, evaluateCapacityStage(filter, session.isCapacityRefineExperiment()));
        boolean brandCovered = filter.isBrandFlexible() || filter.getBrand() != null;
        updated.put(SlotType.BRAND, brandCovered ? SlotStage.REFINED : SlotStage.MISSING);
        updated.put(SlotType.DIMENSIONS, evaluateDimensionStage(filter, session.isAskDimensionsExperiment()));

        updated.forEach((slot, stage) -> {
            SlotStage previous = session.getSlotStages().get(slot);
            session.getSlotStages().put(slot, stage);
            if (previous != SlotStage.REFINED && stage == SlotStage.REFINED) {
                session.getMetrics().incrementSlotsCompleted();
            }
        });
    }

    private static SlotStage evaluateBudgetStage(QueryFilter filter) {
        if (filter.getMinPrice() == null && filter.getMaxPrice() == null) {
            return SlotStage.MISSING;
        }
        if (filter.getMinPrice() != null && filter.getMaxPrice() != null) {
            double span = Math.abs(filter.getMaxPrice() - filter.getMinPrice());
            return span <= 200 ? SlotStage.REFINED : SlotStage.ROUGH;
        }
        return SlotStage.REFINED;
    }

    private static SlotStage evaluateCapacityStage(QueryFilter filter, boolean refineExperiment) {
        if (filter.getMinCapacityKg() == null && filter.getMaxCapacityKg() == null) {
            return SlotStage.MISSING;
        }
        if (!refineExperiment) {
            return SlotStage.REFINED;
        }
        if (filter.getMinCapacityKg() != null && filter.getMaxCapacityKg() != null) {
            int span = Math.abs(filter.getMaxCapacityKg() - filter.getMinCapacityKg());
            return span <= 1 ? SlotStage.REFINED : SlotStage.ROUGH;
        }
        return SlotStage.ROUGH;
    }

    private static SlotStage evaluateDimensionStage(QueryFilter filter, boolean ask) {
        if (!ask) {
            return SlotStage.REFINED;
        }
        boolean hasAny = filter.getWidthCm() != null || filter.getHeightCm() != null || filter.getDepthCm() != null;
        if (!hasAny) {
            return SlotStage.MISSING;
        }
        boolean allPresent = filter.getWidthCm() != null && filter.getHeightCm() != null && filter.getDepthCm() != null;
        return allPresent ? SlotStage.REFINED : SlotStage.ROUGH;
    }

    private SlotType determineNextSlot(ConversationSession session) {
        for (SlotType slot : SlotType.values()) {
            SlotStage stage = session.getSlotStages().get(slot);
            if (slot == SlotType.BRAND && shouldSkipBrand(session)) {
                continue;
            }
            if (slot == SlotType.DIMENSIONS && session.getSlotStages().get(slot) == SlotStage.REFINED) {
                continue;
            }
            if (stage == SlotStage.MISSING || stage == SlotStage.ROUGH) {
                return slot;
            }
        }
        return SlotType.BRAND;
    }

    private boolean shouldSkipBrand(ConversationSession session) {
        QueryFilter filter = session.getFilter();
        if (filter.isBrandFlexible()) {
            return true;
        }
        boolean onlyOneBrand = brandCatalog.getBrands().size() <= 1;
        return filter.getBrand() == null && onlyOneBrand;
    }

    private boolean shouldFinalize(ConversationSession session) {
        Map<SlotType, SlotStage> stages = session.getSlotStages();
        boolean budgetReady = stages.getOrDefault(SlotType.BUDGET, SlotStage.MISSING) == SlotStage.REFINED;
        boolean typeReady = stages.getOrDefault(SlotType.TYPE, SlotStage.MISSING) == SlotStage.REFINED;
        boolean capacityReady = session.isCapacityRefineExperiment()
                ? stages.getOrDefault(SlotType.CAPACITY, SlotStage.MISSING) == SlotStage.REFINED
                : stages.getOrDefault(SlotType.CAPACITY, SlotStage.MISSING) != SlotStage.MISSING;
        return budgetReady && typeReady && capacityReady;
    }

    private List<String> chipsFor(SlotType slotType) {
        return switch (slotType) {
            case BUDGET -> List.of("≤ 500€", "≤ 600€", "≤ 700€");
            case TYPE -> List.of("Front load", "Top load");
            case CAPACITY -> List.of("7kg", "8kg", "9kg");
            case BRAND -> brandCatalog.getBrands().stream()
                    .limit(5)
                    .collect(Collectors.toList());
            case DIMENSIONS -> List.of("60×85×55 cm", "45×90×60 cm");
        };
    }

    private boolean isPurchaseIntent(String text, String selectionHint) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String phrase : PURCHASE_KEY_PHRASES) {
            if (lower.contains(phrase.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        if (selectionHint != null) {
            if (lower.contains(" is ok") || lower.contains(" is okay")
                    || lower.contains(" is fine") || lower.contains(" looks good")
                    || lower.contains(" works for me") || lower.contains(" sounds good")
                    || lower.contains(" that'll do") || lower.contains(" that will do")
                    || lower.contains(" good for me") || lower.contains("就行")) {
                return true;
            }
        }
        return false;
    }

    private String extractSelection(String text,
                                    QueryFilter filter,
                                    List<Product> preview,
                                    List<Product> results) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = QUOTED_SELECTION.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (filter != null && filter.getBrand() != null
                && lower.contains(filter.getBrand().toLowerCase(Locale.ROOT))) {
            return filter.getBrand();
        }
        List<Product> candidates = new ArrayList<>();
        if (preview != null) {
            candidates.addAll(preview);
        }
        if (results != null) {
            candidates.addAll(results);
        }
        for (Product product : candidates) {
            if (product == null) {
                continue;
            }
            if (product.getModel() != null
                    && lower.contains(product.getModel().toLowerCase(Locale.ROOT))) {
                return (product.getBrand() == null ? "" : product.getBrand() + " ")
                        + product.getModel();
            }
            if (product.getBrand() != null
                    && lower.contains(product.getBrand().toLowerCase(Locale.ROOT))) {
                return product.getBrand();
            }
        }
        return null;
    }

    private String buildPurchaseClosing(String selection, String localeHint) {
        String safeSelection = selection == null ? "" : selection;
        return selection == null
                ? "Great, I'll wrap that up for you. Let me know if you need anything else."
                : "Great choice on " + safeSelection + "! I'll wrap that up—just shout if you need anything else.";
    }

    private List<SlotSnapshot> buildSlotSnapshots(ConversationSession session) {
        QueryFilter filter = session.getFilter();
        List<SlotSnapshot> snapshots = new ArrayList<>();
        snapshots.add(new SlotSnapshot("budget", session.getSlotStages().get(SlotType.BUDGET).name(), describeBudget(filter)));
        snapshots.add(new SlotSnapshot("type", session.getSlotStages().get(SlotType.TYPE).name(), filter.getType()));
        snapshots.add(new SlotSnapshot("capacity", session.getSlotStages().get(SlotType.CAPACITY).name(), describeCapacity(filter)));
        String brandSnapshot = filter.isBrandFlexible() ? "Any brand" : filter.getBrand();
        snapshots.add(new SlotSnapshot("brand", session.getSlotStages().get(SlotType.BRAND).name(), brandSnapshot));
        snapshots.add(new SlotSnapshot("dimensions", session.getSlotStages().get(SlotType.DIMENSIONS).name(), describeDimensions(filter)));
        return snapshots;
    }

    private static String describeBudget(QueryFilter filter) {
        if (filter.getMinPrice() == null && filter.getMaxPrice() == null) {
            return null;
        }
        if (filter.getMinPrice() != null && filter.getMaxPrice() != null) {
            return "€" + filter.getMinPrice().intValue() + " - €" + filter.getMaxPrice().intValue();
        }
        if (filter.getMaxPrice() != null) {
            return "≤ €" + filter.getMaxPrice().intValue();
        }
        return "≥ €" + filter.getMinPrice().intValue();
    }

    private static String describeCapacity(QueryFilter filter) {
        if (filter.getMinCapacityKg() == null && filter.getMaxCapacityKg() == null) {
            return null;
        }
        if (filter.getMinCapacityKg() != null && filter.getMaxCapacityKg() != null) {
            return filter.getMinCapacityKg() + "-" + filter.getMaxCapacityKg() + "kg";
        }
        if (filter.getMaxCapacityKg() != null) {
            return "≤ " + filter.getMaxCapacityKg() + "kg";
        }
        return "≥ " + filter.getMinCapacityKg() + "kg";
    }

    private static String describeDimensions(QueryFilter filter) {
        if (filter.getWidthCm() == null && filter.getHeightCm() == null && filter.getDepthCm() == null) {
            return null;
        }
        String w = filter.getWidthCm() == null ? "?" : String.format("%.0f", filter.getWidthCm());
        String h = filter.getHeightCm() == null ? "?" : String.format("%.0f", filter.getHeightCm());
        String d = filter.getDepthCm() == null ? "?" : String.format("%.0f", filter.getDepthCm());
        return w + "×" + h + "×" + d + " cm";
    }

    private static List<String> previewHighlights(List<Product> preview) {
        if (preview == null || preview.isEmpty()) {
            return List.of();
        }
        return preview.stream()
                .map(product -> product.getBrand() + " " + product.getModel())
                .toList();
    }

    private static Double validPrice(Double value) {
        if (value == null) {
            return null;
        }
        if (value < 50) {
            return null;
        }
        return value;
    }

}
