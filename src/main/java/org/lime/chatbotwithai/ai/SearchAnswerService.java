package org.lime.chatbotwithai.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.lime.chatbotwithai.product.Product;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class SearchAnswerService {

    private final ChatClient chatClient;
    private final ObjectMapper mapper;

    public SearchAnswerService(ChatClient.Builder builder, ObjectMapper mapper) {
        this.chatClient = builder.build();
        this.mapper = mapper;
    }

    public String explain(String userQuery, QueryFilter filter, List<Product> results) {
        List<Product> ordered = results.stream()
                .sorted(Comparator.comparing(Product::getPrice, Comparator.nullsLast(Double::compareTo)).reversed())
                .toList(); // Higher-priced first

        String items = ordered.stream()
                .map(SearchAnswerService::formatProductLine)
                .collect(Collectors.joining("\n"));

        String filterSummary = renderFilterSummary(filter);
        String filterJson = renderFilterJson(filter);

        var prompt = """
            You are the closing voice of a guided washing-machine assistant.

            User request (latest wording):
            "%s"

            Collected filters (human summary):
            %s

            Filters JSON:
            %s

            Ranked shortlist (only options you may mention):
            %s

            Requirements:
            1) Open with one sentence summarising how well the shortlist covers budget, type, capacity, and dimensions.
            2) Then list each product as a bullet. For every item:
               - Explain why it matches the filters using explicit numbers (budget difference, capacity match, size fit, etc.).
               - If something is off, prepend "⚠" and say exactly which constraint is missed.
            3) Finish with a heading "Trade-off ideas:" followed by 1–2 short bullets. Use actionable levers like "+€50 budget stretch" or "switch brand to Bosch" based on the filters and items.
            4) If the shortlist is empty, explain the likely reason and suggest the most helpful filter change instead of the list in step 2.
            5) Language must match the user's language.
            6) Stay factual: base every statement strictly on the data provided above.
            """.formatted(userQuery, filterSummary, filterJson, items);

        return chatClient.prompt().user(prompt).call().content();
    }

    private static String formatProductLine(Product product) {
        String id = product.getId() == null ? "unknown" : product.getId().toString();
        String brand = safeValue(product.getBrand());
        String model = safeValue(product.getModel());
        String type = safeValue(product.getType());
        String price = priceLabel(product.getPrice());
        String capacity = capacityLabel(product.getCapacityKg());
        String size = formatSize(product.getWidthCm(), product.getHeightCm(), product.getDepthCm());
        String description = shorten(product.getDescription());
        return "- id:" + id + " | " + brand + " " + model + " (" + type + ") " + price
                + " | " + capacity + " | size " + size + " | brand " + brand + " | desc " + description;
    }

    private String renderFilterJson(QueryFilter filter) {
        QueryFilter safeFilter = filter == null ? new QueryFilter() : filter;
        ObjectNode node = mapper.createObjectNode();
        node.put("brand", summarizeBrandValue(filter));
        putNullableString(node, "type", safeFilter.getType());
        putNullableNumber(node, "minPrice", safeFilter.getMinPrice());
        putNullableNumber(node, "maxPrice", safeFilter.getMaxPrice());
        putNullableInt(node, "minCapacityKg", safeFilter.getMinCapacityKg());
        putNullableInt(node, "maxCapacityKg", safeFilter.getMaxCapacityKg());
        putNullableNumber(node, "widthCm", safeFilter.getWidthCm());
        putNullableNumber(node, "heightCm", safeFilter.getHeightCm());
        putNullableNumber(node, "depthCm", safeFilter.getDepthCm());
        node.put("brandFlexible", filter != null && filter.isBrandFlexible());
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            return node.toString();
        }
    }

    private static void putNullableString(ObjectNode node, String key, String value) {
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, value);
        }
    }

    private static void putNullableNumber(ObjectNode node, String key, Double value) {
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, value);
        }
    }

    private static void putNullableInt(ObjectNode node, String key, Integer value) {
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, value);
        }
    }

    private static String priceLabel(Double price) {
        if (price == null) {
            return "price unknown";
        }
        return String.format(Locale.ROOT, "price €%.0f", price);
    }

    private static String capacityLabel(Integer capacityKg) {
        if (capacityKg == null) {
            return "capacity unknown";
        }
        return capacityKg + "kg";
    }

    private static String safeValue(String value) {
        if (value == null) {
            return "unknown";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "unknown" : trimmed;
    }

    private static String formatSize(Double w, Double h, Double d) {
        if (w == null || h == null || d == null) {
            return "unknown";
        }
        return String.format(Locale.ROOT, "%.0f×%.0f×%.0f cm", w, h, d);
    }

    private static String shorten(String description) {
        if (description == null) {
            return "n/a";
        }
        String trimmed = description.trim();
        return trimmed.length() > 160 ? trimmed.substring(0, 157) + "..." : trimmed;
    }

    private static String renderFilterSummary(QueryFilter filter) {
        if (filter == null) {
            return "Budget unknown; type unknown; capacity unknown; brand any brand; no dimension constraints.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Budget ").append(describeBudget(filter)).append("; ");
        sb.append("Type ").append(filter.getType() == null ? "open" : filter.getType()).append("; ");
        sb.append("Capacity ").append(describeCapacity(filter)).append("; ");
        sb.append("Brand ").append(describeBrand(filter)).append("; ");
        sb.append("Dimensions ").append(describeDimensions(filter));
        return sb.toString();
    }

    private static String describeBrand(QueryFilter filter) {
        if (filter == null || filter.isBrandFlexible()) {
            return "any brand";
        }
        return filter.getBrand() == null ? "open" : filter.getBrand();
    }

    private static String summarizeBrandValue(QueryFilter filter) {
        if (filter == null || filter.isBrandFlexible()) {
            return "any brand";
        }
        return filter.getBrand() == null ? "open" : filter.getBrand();
    }

    private static String describeBudget(QueryFilter filter) {
        if (filter.getMinPrice() == null && filter.getMaxPrice() == null) {
            return "not specified";
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
            return "not specified";
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
            return "not specified";
        }
        String w = filter.getWidthCm() == null ? "?" : String.format("%.0fcm", filter.getWidthCm());
        String h = filter.getHeightCm() == null ? "?" : String.format("%.0fcm", filter.getHeightCm());
        String d = filter.getDepthCm() == null ? "?" : String.format("%.0fcm", filter.getDepthCm());
        return "%s × %s × %s".formatted(w, h, d);
    }
}
