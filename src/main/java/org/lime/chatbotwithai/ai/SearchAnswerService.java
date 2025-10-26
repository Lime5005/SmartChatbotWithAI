package org.lime.chatbotwithai.ai;

import org.lime.chatbotwithai.product.Product;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class SearchAnswerService {

    private static final double DIMENSION_TOLERANCE_CM = 1.0;

    public String explain(String userQuery, QueryFilter filter, List<Product> results) {
        if (results == null || results.isEmpty()) {
            return buildEmptyExplanation(filter);
        }

        StringBuilder message = new StringBuilder();
        message.append(summarySentence(filter, results)).append("\n");
        message.append(bulletsFor(results, filter));
        message.append("\nTrade-off ideas:\n");
        tradeOffIdeas(filter, results).forEach(idea -> message.append("- ").append(idea).append("\n"));
        return message.toString().trim();
    }

    private static String buildEmptyExplanation(QueryFilter filter) {
        List<String> hints = new ArrayList<>();
        if (filter != null) {
            if (filter.getMaxPrice() != null) {
                hints.add("consider raising the budget a little");
            }
            if (filter.getBrand() != null && !filter.isBrandFlexible()) {
                hints.add("try allowing more brands");
            }
            if (filter.getMinCapacityKg() != null || filter.getMaxCapacityKg() != null) {
                hints.add("widen the capacity range");
            }
            if (filter.getWidthCm() != null || filter.getHeightCm() != null || filter.getDepthCm() != null) {
                hints.add("loosen the size constraints");
            }
        }
        String advice = hints.isEmpty()
                ? "try broadening one of the filters."
                : hints.stream().limit(2).collect(Collectors.joining(" or "));
        return "No shortlisted washing machines matched all of the filters; " + advice;
    }

    private static String summarySentence(QueryFilter filter, List<Product> results) {
        List<String> matched = new ArrayList<>();
        List<String> strained = new ArrayList<>();

        if (filter != null) {
            if (filter.getMaxPrice() != null || filter.getMinPrice() != null) {
                (allRespectBudget(results, filter) ? matched : strained).add("budget");
            }
            if (filter.getType() != null) {
                (allMatchType(results, filter) ? matched : strained).add("type");
            }
            if (filter.getMinCapacityKg() != null || filter.getMaxCapacityKg() != null) {
                (allMatchCapacity(results, filter) ? matched : strained).add("capacity");
            }
            if (hasDimensionFilter(filter)) {
                (allMatchDimensions(results, filter) ? matched : strained).add("dimensions");
            }
        }

        if (matched.isEmpty() && strained.isEmpty()) {
            return "Here’s how the shortlist lines up with your preferences.";
        }
        StringBuilder summary = new StringBuilder();
        if (!matched.isEmpty()) {
            summary.append("The shortlist keeps ");
            summary.append(joinWithAnd(matched));
            summary.append(" on target");
        }
        if (!strained.isEmpty()) {
            if (!matched.isEmpty()) {
                summary.append(", while");
            }
            summary.append(" you may need to adjust ");
            summary.append(joinWithAnd(strained));
        }
        summary.append(".");
        return summary.toString();
    }

    private static String bulletsFor(List<Product> results, QueryFilter filter) {
        StringBuilder block = new StringBuilder();
        for (Product product : results) {
            block.append("- ").append(describeProduct(product, filter)).append("\n");
        }
        return block.toString();
    }

    private static String describeProduct(Product product, QueryFilter filter) {
        List<String> notes = new ArrayList<>();
        notes.add(priceFragment(product, filter));
        notes.add(capacityFragment(product, filter));
        notes.add(dimensionFragment(product, filter));
        String extras = notes.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.joining("; "));
        return "%s %s — %s".formatted(
                safe(product.getBrand()),
                safe(product.getModel()),
                extras.isBlank() ? "details unavailable" : extras
        );
    }

    private static String priceFragment(Product product, QueryFilter filter) {
        Double price = product.getPrice();
        if (price == null) {
            return "price unknown";
        }
        if (filter == null) {
            return "€%s".formatted(formatNumber(price));
        }
        Double min = filter.getMinPrice();
        Double max = filter.getMaxPrice();
        if (max != null && price > max) {
            return "⚠ €%s (exceeds €%s cap by €%s)".formatted(
                    formatNumber(price),
                    formatNumber(max),
                    formatNumber(price - max)
            );
        }
        if (min != null && price < min) {
            return "⚠ €%s (below €%s minimum by €%s)".formatted(
                    formatNumber(price),
                    formatNumber(min),
                    formatNumber(min - price)
            );
        }
        if (max != null) {
            return "€%s (comfortably under the €%s ceiling)".formatted(
                    formatNumber(price),
                    formatNumber(max)
            );
        }
        if (min != null) {
            return "€%s (above the €%s floor)".formatted(
                    formatNumber(price),
                    formatNumber(min)
            );
        }
        return "€%s".formatted(formatNumber(price));
    }

    private static String capacityFragment(Product product, QueryFilter filter) {
        Integer capacity = product.getCapacityKg();
        if (capacity == null) {
            return "capacity unknown";
        }
        if (filter == null) {
            return "%dkg drum".formatted(capacity);
        }
        Integer min = filter.getMinCapacityKg();
        Integer max = filter.getMaxCapacityKg();
        if (max != null && capacity > max) {
            return "⚠ %dkg (over the %dkg ceiling)".formatted(capacity, max);
        }
        if (min != null && capacity < min) {
            return "⚠ %dkg (below the %dkg minimum)".formatted(capacity, min);
        }
        if (min != null || max != null) {
            return "%dkg drum (within your range)".formatted(capacity);
        }
        return "%dkg drum".formatted(capacity);
    }

    private static String dimensionFragment(Product product, QueryFilter filter) {
        Double w = product.getWidthCm();
        Double h = product.getHeightCm();
        Double d = product.getDepthCm();
        String size = sizeLabel(w, h, d);
        if (!hasDimensionFilter(filter) || size.equals("unknown")) {
            return "size " + size;
        }
        if (matchesDimensions(product, filter)) {
            return "size %s (fits your space)".formatted(size);
        }
        return "⚠ size %s (check against your space)".formatted(size);
    }

    private static List<String> tradeOffIdeas(QueryFilter filter, List<Product> results) {
        List<String> ideas = new ArrayList<>();
        if (filter == null) {
            ideas.add("add a budget or capacity range to narrow the shortlist");
            return ideas;
        }
        if (filter.getMaxPrice() != null && results.stream().anyMatch(p -> exceeds(p.getPrice(), filter.getMaxPrice()))) {
            ideas.add("increase the budget to around €" + formatNumber(filter.getMaxPrice() + 50));
        }
        if (filter.getBrand() != null && !filter.isBrandFlexible()) {
            ideas.add("set brand flexibility to \"any\" to see alternatives");
        }
        if (ideas.size() < 2 && hasDimensionFilter(filter)) {
            ideas.add("allow ±2 cm on one dimension to surface more models");
        }
        if (ideas.isEmpty()) {
            ideas.add("keep exploring capacity or feature trade-offs if needed");
        }
        return ideas.stream().limit(2).toList();
    }

    private static boolean allRespectBudget(List<Product> results, QueryFilter filter) {
        return results.stream().allMatch(p -> respectsBudget(p, filter));
    }

    private static boolean respectsBudget(Product p, QueryFilter filter) {
        Double price = p.getPrice();
        if (price == null) {
            return false;
        }
        Double min = filter.getMinPrice();
        Double max = filter.getMaxPrice();
        if (min != null && price < min) {
            return false;
        }
        if (max != null && price > max) {
            return false;
        }
        return true;
    }

    private static boolean allMatchType(List<Product> results, QueryFilter filter) {
        String desired = safeLower(filter.getType());
        return results.stream().allMatch(p -> desired.equals(safeLower(p.getType())));
    }

    private static boolean allMatchCapacity(List<Product> results, QueryFilter filter) {
        return results.stream().allMatch(p -> matchesCapacity(p, filter));
    }

    private static boolean matchesCapacity(Product p, QueryFilter filter) {
        Integer cap = p.getCapacityKg();
        if (cap == null) {
            return false;
        }
        Integer min = filter.getMinCapacityKg();
        Integer max = filter.getMaxCapacityKg();
        if (min != null && cap < min) {
            return false;
        }
        if (max != null && cap > max) {
            return false;
        }
        return true;
    }

    private static boolean allMatchDimensions(List<Product> results, QueryFilter filter) {
        return results.stream().allMatch(p -> matchesDimensions(p, filter));
    }

    private static boolean matchesDimensions(Product product, QueryFilter filter) {
        if (!hasDimensionFilter(filter)) {
            return true;
        }
        Double w = product.getWidthCm();
        Double h = product.getHeightCm();
        Double d = product.getDepthCm();
        if (w == null || h == null || d == null) {
            return false;
        }
        return withinTolerance(w, filter.getWidthCm())
                && withinTolerance(h, filter.getHeightCm())
                && withinTolerance(d, filter.getDepthCm());
    }

    private static boolean withinTolerance(Double actual, Double requested) {
        if (requested == null) {
            return true;
        }
        return Math.abs(actual - requested) <= DIMENSION_TOLERANCE_CM;
    }

    private static boolean hasDimensionFilter(QueryFilter filter) {
        return filter != null && (filter.getWidthCm() != null || filter.getHeightCm() != null || filter.getDepthCm() != null);
    }

    private static String joinWithAnd(List<String> terms) {
        if (terms.size() <= 1) {
            return terms.isEmpty() ? "" : terms.get(0);
        }
        return String.join(", ", terms.subList(0, terms.size() - 1)) + " and " + terms.get(terms.size() - 1);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String sizeLabel(Double w, Double h, Double d) {
        if (w == null || h == null || d == null) {
            return "unknown";
        }
        return "%s×%s×%s cm".formatted(formatNumber(w), formatNumber(h), formatNumber(d));
    }

    private static String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private static boolean exceeds(Double price, Double limit) {
        return price != null && limit != null && price > limit;
    }
}
