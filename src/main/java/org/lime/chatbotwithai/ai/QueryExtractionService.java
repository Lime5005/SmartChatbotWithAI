package org.lime.chatbotwithai.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.lime.chatbotwithai.product.BrandCatalog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QueryExtractionService {

    private final ChatClient chatClient;
    private final ObjectMapper mapper;
    private final BrandCatalog brandCatalog;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+[\\d,.]*)");
    private static final Pattern DIMENSION_PATTERN = Pattern.compile("(\\d{2,})\\s*[x×]\\s*(\\d{2,})\\s*[x×]\\s*(\\d{2,})");
    private static final Pattern PRICE_RANGE_PATTERN = Pattern.compile("(\\d{2,})\\s*(?:-|to)\\s*(\\d{2,})(?!\\s*(?:cm|mm|kg|litre|liter))");
    private static final Pattern CAPACITY_RANGE_PATTERN = Pattern.compile("(\\d{1,2})\\s*(?:-|to)\\s*(\\d{1,2})\\s*kg");
    private static final Pattern CAPACITY_PATTERN = Pattern.compile("(\\d{1,2})\\s*kg");
    private static final String[] MAX_PRICE_HINTS_NORMALIZED = {
            "under", "below", "less", "less than", "max", "budget", "plafond", "moins de", "inferieur", "inferior", "jusqu a", "up to"
    };
    private static final String[] MIN_PRICE_HINTS_NORMALIZED = {
            "over", "above", "at least", "minimum", "plus de", "au moins", "superieur", "superior"
    };
    private static final String[] MAX_PRICE_HINTS_LOCALE = {
            "within budget", "cap", "upper limit"
    };
    private static final String[] MIN_PRICE_HINTS_LOCALE = {
            "minimum spend", "floor", "starting from"
    };
    private static final String[] TOP_LOAD_HINTS_NORMALIZED = {
            "top load", "top-load", "toploader", "toplader", "top"
    };
    private static final String[] TOP_LOAD_HINTS_LOCALE = {
            "vertical load", "upright washer"
    };
    private static final String[] FRONT_LOAD_HINTS_NORMALIZED = {
            "front load", "front-load", "frontloader", "front", "hublot"
    };
    private static final String[] FRONT_LOAD_HINTS_LOCALE = {
            "horizontal drum", "side door"
    };
    private static final String[] BRAND_RELAX_PHRASES = {
            "any brand", "any other brand", "other brand", "different brand",
            "open on brand", "brand doesn't matter", "brand does not matter",
            "brand isn't important", "no brand preference", "another brand",
            "any brands", "brand flexible", "brand free",
            "任何品牌", "别的品牌", "其他品牌", "还有别的品牌", "还有其他品牌",
            "品牌不限", "品牌无所谓", "没有品牌偏好", "换个品牌", "别的牌子", "其他牌子"
    };

    public QueryExtractionService(ChatClient.Builder builder,
                                  ObjectMapper mapper,
                                  BrandCatalog brandCatalog) {
        this.chatClient = builder.build();
        this.mapper = mapper;
        this.brandCatalog = brandCatalog;
    }

    public QueryFilter extract(String userQuery) {
        var tmpl = new PromptTemplate("""
            You are an assistant that extracts **structured filters** for washing-machine shopping.

            Supported fields (any may be null): 
            - brand (string)
            - type ("front" or "top")
            - minPrice (number), maxPrice (number)
            - minCapacityKg (integer), maxCapacityKg (integer)
            - widthCm (number), heightCm (number), depthCm (number)

            Rules:
            - If user gives a price range like "400-600", set minPrice=400, maxPrice=600.
            - "front load" => type="front"; "top load" => type="top".
            - If user gives physical dimensions like "60x85x55", map them to widthCm=60, heightCm=85, depthCm=55.
            - Return ONLY valid JSON with these fields; do not include extra keys.

            User query: "{q}"
            """);
        var prompt = tmpl.create(Map.of("q", userQuery));

        // Ask Spring AI for structured JSON output
        var resp = chatClient.prompt(prompt)
                .call()
                .content();
        QueryFilter filter = parseFilter(resp);
        return enrichWithHeuristics(filter, userQuery);
    }

    private QueryFilter parseFilter(String response) {
        try {
            return mapper.readValue(response, QueryFilter.class);
        } catch (Exception ignored) {
            return QueryFilter.builder().build();
        }
    }

    private QueryFilter enrichWithHeuristics(QueryFilter filter, String userQuery) {
        QueryFilter result = filter != null ? filter : QueryFilter.builder().build();
        String lower = userQuery.toLowerCase(Locale.ROOT);
        String normalized = lower.replaceAll("[^a-z0-9]+", " ").trim();
        String textForPrice = stripDimensions(result, lower);

        if (result.getBrand() == null) {
            brandCatalog.getBrands().stream()
                    .filter(brand -> lower.contains(brand.toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .ifPresent(result::setBrand);
        }

        if (result.getType() == null) {
            boolean topHint = containsAnyNormalized(normalized, TOP_LOAD_HINTS_NORMALIZED)
                    || containsAny(lower, TOP_LOAD_HINTS_LOCALE);
            boolean frontHint = containsAnyNormalized(normalized, FRONT_LOAD_HINTS_NORMALIZED)
                    || containsAny(lower, FRONT_LOAD_HINTS_LOCALE);
            if (topHint && !frontHint) {
                result.setType("top");
            } else if (frontHint && !topHint) {
                result.setType("front");
            }
        }

        applyCapacityHeuristics(result, lower);

        boolean hasCurrency = lower.contains("€") || lower.contains("eur") || lower.contains("euro");
        boolean hasPriceWord = normalized.contains(" price ") || normalized.contains(" cost ");
        boolean hasBudgetWord = normalized.contains(" budget ");
        boolean maxHint = containsAnyNormalized(normalized, MAX_PRICE_HINTS_NORMALIZED)
                || containsAny(lower, MAX_PRICE_HINTS_LOCALE);
        boolean minHint = containsAnyNormalized(normalized, MIN_PRICE_HINTS_NORMALIZED)
                || containsAny(lower, MIN_PRICE_HINTS_LOCALE);
        boolean hasRangeSignal = PRICE_RANGE_PATTERN.matcher(lower).find();

        if (result.getMinPrice() == null || result.getMaxPrice() == null) {
            List<Double> numbers = extractNumbers(textForPrice);
            numbers.removeIf(value -> value < 50);
            boolean hasPriceSignal = hasCurrency || hasPriceWord || hasBudgetWord || maxHint || minHint;
            boolean dimensionWords = normalized.contains(" width ") || normalized.contains(" height ")
                    || normalized.contains(" depth ") || normalized.contains(" dimension ")
                    || normalized.contains(" size ");
            if (numbers.size() >= 2 && !dimensionWords && (hasPriceSignal || hasRangeSignal)) {
                numbers.sort(Double::compareTo);
                double min = numbers.get(0);
                double max = numbers.get(numbers.size() - 1);
                boolean looksLikePriceRange = max >= 50 || hasCurrency || hasPriceWord || hasBudgetWord;
                if (looksLikePriceRange) {
                    if (result.getMinPrice() == null) {
                        result.setMinPrice(min);
                    }
                    if (result.getMaxPrice() == null) {
                        result.setMaxPrice(max);
                    }
                }
            } else if (!numbers.isEmpty()) {
                double value = numbers.get(0);
                boolean maxSymbol = lower.contains("≤") || lower.contains("<=") || lower.contains("up to");
                boolean minSymbol = lower.contains("≥") || lower.contains(">=");
                boolean valueLooksLikePrice = value >= 50;
                boolean qualifiesMax = maxHint || maxSymbol || hasCurrency || hasBudgetWord;
                boolean qualifiesMin = minHint || minSymbol || hasCurrency;
                if (qualifiesMax && (valueLooksLikePrice || hasCurrency || maxHint || hasBudgetWord)) {
                    if (result.getMaxPrice() == null) {
                        result.setMaxPrice(value);
                    }
                } else if (qualifiesMin && (valueLooksLikePrice || hasCurrency || minHint)) {
                    if (result.getMinPrice() == null) {
                        result.setMinPrice(value);
                    }
                } else if (hasBudgetWord && result.getMaxPrice() == null) {
                    result.setMaxPrice(value);
                }
            }
        }

        return relaxBrandIfRequested(result, lower);
    }

    private static void applyCapacityHeuristics(QueryFilter filter, String lower) {
        if (filter == null) {
            return;
        }
        Matcher rangeMatcher = CAPACITY_RANGE_PATTERN.matcher(lower);
        if (rangeMatcher.find()) {
            int min = parseIntSafe(rangeMatcher.group(1));
            int max = parseIntSafe(rangeMatcher.group(2));
            if (min > 0 && max > 0) {
                if (filter.getMinCapacityKg() == null) {
                    filter.setMinCapacityKg(Math.min(min, max));
                }
                if (filter.getMaxCapacityKg() == null) {
                    filter.setMaxCapacityKg(Math.max(min, max));
                }
                return;
            }
        }
        Matcher singleMatcher = CAPACITY_PATTERN.matcher(lower);
        if (singleMatcher.find()) {
            int value = parseIntSafe(singleMatcher.group(1));
            if (value > 0) {
                if (filter.getMinCapacityKg() == null) {
                    filter.setMinCapacityKg(value);
                }
                if (filter.getMaxCapacityKg() == null) {
                    filter.setMaxCapacityKg(value);
                }
            }
        }
    }

    private static String stripDimensions(QueryFilter filter, String text) {
        Matcher matcher = DIMENSION_PATTERN.matcher(text);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            double width = parseNumber(matcher.group(1));
            double height = parseNumber(matcher.group(2));
            double depth = parseNumber(matcher.group(3));
            if (filter.getWidthCm() == null) {
                filter.setWidthCm(width);
            }
            if (filter.getHeightCm() == null) {
                filter.setHeightCm(height);
            }
            if (filter.getDepthCm() == null) {
                filter.setDepthCm(depth);
            }
            matcher.appendReplacement(sanitized, " ");
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    private static double parseNumber(String token) {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static int parseIntSafe(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<Double> extractNumbers(String text) {
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        List<Double> values = new ArrayList<>();
        while (matcher.find()) {
            double parsed = parseLocaleNumber(matcher.group(1));
            if (!Double.isNaN(parsed)) {
                values.add(parsed);
            }
        }
        return values;
    }

    private static double parseLocaleNumber(String token) {
        if (token == null || token.isBlank()) {
            return Double.NaN;
        }
        String normalized = token.replaceAll("\\s", "");
        int lastComma = normalized.lastIndexOf(',');
        int lastDot = normalized.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                normalized = normalized.replace(".", "");
                normalized = normalized.replace(',', '.');
            } else {
                normalized = normalized.replace(",", "");
            }
        } else if (lastComma >= 0) {
            int decimals = normalized.length() - lastComma - 1;
            if (decimals == 3) {
                normalized = normalized.replace(",", "");
            } else {
                normalized = normalized.replace(',', '.');
            }
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    private static boolean containsAny(String text, String[] tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyNormalized(String normalized, String[] tokens) {
        if (normalized.isEmpty()) {
            return false;
        }
        String padded = " " + normalized + " ";
        for (String token : tokens) {
            String cleaned = token.trim().replaceAll("\\s+", " ");
            if (cleaned.isEmpty()) continue;
            if (padded.contains(" " + cleaned + " ")) {
                return true;
            }
        }
        return false;
    }

    private static QueryFilter relaxBrandIfRequested(QueryFilter filter, String lower) {
        if (containsAny(lower, BRAND_RELAX_PHRASES)) {
            filter.setBrand(null);
            filter.setBrandFlexible(true);
            return filter;
        }
        String brand = filter.getBrand();
        if (brand == null) {
            return filter;
        }
        String brandLower = brand.toLowerCase(Locale.ROOT);
        if (lower.contains("other than " + brandLower)
                || lower.contains("not " + brandLower)
                || lower.contains("besides " + brandLower)
                || lower.contains("except " + brandLower)
                || lower.contains("outside " + brandLower)
                || lower.contains("apart from " + brandLower)) {
            filter.setBrand(null);
            filter.setBrandFlexible(true);
        }
        return filter;
    }
}
