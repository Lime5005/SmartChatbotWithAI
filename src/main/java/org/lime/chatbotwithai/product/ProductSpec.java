package org.lime.chatbotwithai.product;

import org.springframework.data.jpa.domain.Specification;

public class ProductSpec {

    public static Specification<Product> brandEquals(String brand) {
        return (root, q, cb) -> brand == null ? null
                : cb.equal(cb.lower(root.get("brand")), brand.toLowerCase());
    }

    public static Specification<Product> typeEquals(String type) {
        return (root, q, cb) -> type == null ? null
                : cb.equal(cb.lower(root.get("type")), type.toLowerCase());
    }

    public static Specification<Product> capacityBetween(Integer minKg, Integer maxKg) {
        return (root, q, cb) -> {
            if (minKg == null && maxKg == null) {
                return null;
            }
            if (minKg != null && maxKg != null) {
                return cb.between(root.get("capacityKg"), minKg, maxKg);
            }
            if (minKg != null) {
                return cb.greaterThanOrEqualTo(root.get("capacityKg"), minKg);
            }
            return cb.lessThanOrEqualTo(root.get("capacityKg"), maxKg);
        };
    }

    public static Specification<Product> priceBetween(Double min, Double max) {
        return (root, q, cb) -> {
            if (min == null && max == null) return null;
            if (min != null && max != null) return cb.between(root.get("price"), min, max);
            if (min != null) return cb.greaterThanOrEqualTo(root.get("price"), min);
            return cb.lessThanOrEqualTo(root.get("price"), max);
        };
    }

    public static Specification<Product> dimensionsCloseTo(Double width, Double height, Double depth, double tolerance) {
        Specification<Product> spec = null;
        if (width != null) {
            Specification<Product> widthSpec = (root, q, cb) ->
                    cb.between(root.get("widthCm"), width - tolerance, width + tolerance);
            spec = spec == null ? widthSpec : spec.and(widthSpec);
        }
        if (height != null) {
            Specification<Product> heightSpec = (root, q, cb) ->
                    cb.between(root.get("heightCm"), height - tolerance, height + tolerance);
            spec = spec == null ? heightSpec : spec.and(heightSpec);
        }
        if (depth != null) {
            Specification<Product> depthSpec = (root, q, cb) ->
                    cb.between(root.get("depthCm"), depth - tolerance, depth + tolerance);
            spec = spec == null ? depthSpec : spec.and(depthSpec);
        }
        return spec;
    }
}
