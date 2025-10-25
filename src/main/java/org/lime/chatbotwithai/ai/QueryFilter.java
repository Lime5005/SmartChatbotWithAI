package org.lime.chatbotwithai.ai;

import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class QueryFilter {
    private String brand;       // e.g. Bosch
    private String type;        // front|top
    private Double minPrice;    // 400
    private Double maxPrice;    // 600
    private Integer minCapacityKg;
    private Integer maxCapacityKg;
    private Double widthCm;
    private Double heightCm;
    private Double depthCm;
    private boolean brandFlexible; // true if user explicitly accepts any brand
}
