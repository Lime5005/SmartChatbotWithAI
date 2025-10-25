package org.lime.chatbotwithai.product;

import org.lime.chatbotwithai.ai.QueryFilter;
import org.lime.chatbotwithai.ai.SemanticRerankService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.lime.chatbotwithai.product.ProductSpec.*;

@Service
public class ProductSearchService {

    private final ProductRepository repository;
    private final SemanticRerankService reranker;

    public ProductSearchService(ProductRepository repository,
                                SemanticRerankService reranker) {
        this.repository = repository;
        this.reranker = reranker;
    }

    public List<Product> preview(QueryFilter filter, int limit) {
        Specification<Product> spec = buildCoreSpec(filter);
        Pageable pageable = PageRequest.of(0, Math.max(1, limit), Sort.by(Sort.Direction.DESC, "price")); // Recommand higher-priced first
        if (spec == null) {
            Page<Product> page = repository.findAll(pageable);
            return page.hasContent() ? page.getContent() : Collections.emptyList();
        }
        return repository.findAll(spec, pageable).getContent();
    }

    public List<Product> finalResults(String query, QueryFilter filter, int limit, double dimensionTolerance) {
        Specification<Product> spec = buildFullSpec(filter, dimensionTolerance);
        List<Product> candidates = selectCandidates(spec, limit);
        if (candidates.isEmpty()) {
            return candidates;
        }
        String rerankQuery = Optional.ofNullable(query).filter(q -> !q.isBlank())
                .orElseGet(() -> buildSearchQuery(filter));
        List<Product> reranked = reranker.rerank(rerankQuery, candidates, limit);
        if (reranked.size() > limit) {
            return List.copyOf(reranked.subList(0, limit));
        }
        return List.copyOf(reranked);
    }

    private Specification<Product> buildCoreSpec(QueryFilter filter) {
        if (filter == null) {
            return null;
        }
        Specification<Product> spec = Specification.anyOf(
                brandEquals(filter.getBrand()))
                .and(typeEquals(filter.getType()))
                .and(priceBetween(filter.getMinPrice(), filter.getMaxPrice()))
                .and(capacityBetween(filter.getMinCapacityKg(), filter.getMaxCapacityKg()));
        return spec;
    }

    private List<Product> selectCandidates(Specification<Product> spec, int limit) {
        int fetchSize = Math.max(limit * 4, 40);
        Pageable pageable = PageRequest.of(0, fetchSize, Sort.by(Sort.Direction.DESC, "price"));
        if (spec == null) {
            return repository.findAll(pageable).getContent();
        }
        return repository.findAll(spec, pageable).getContent();
    }

    private Specification<Product> buildFullSpec(QueryFilter filter, double dimensionTolerance) {
        Specification<Product> spec = buildCoreSpec(filter);
        Specification<Product> dimensionSpec = dimensionsCloseTo(
                filter.getWidthCm(),
                filter.getHeightCm(),
                filter.getDepthCm(),
                dimensionTolerance);
        if (dimensionSpec != null) {
            spec = spec == null ? dimensionSpec : spec.and(dimensionSpec);
        }
        return spec;
    }

    private String buildSearchQuery(QueryFilter filter) {
        if (filter == null) {
            return "washing machine best match";
        }
        StringBuilder sb = new StringBuilder("washing machine");
        if (filter.getBrand() != null) {
            sb.append(" brand ").append(filter.getBrand());
        }
        if (filter.getType() != null) {
            sb.append(" type ").append(filter.getType());
        }
        if (filter.getMinPrice() != null || filter.getMaxPrice() != null) {
            sb.append(" price ");
            if (filter.getMinPrice() != null) {
                sb.append("from ").append(filter.getMinPrice().intValue());
            }
            if (filter.getMaxPrice() != null) {
                sb.append(" up to ").append(filter.getMaxPrice().intValue());
            }
        }
        if (filter.getMinCapacityKg() != null || filter.getMaxCapacityKg() != null) {
            sb.append(" capacity ");
            if (filter.getMinCapacityKg() != null) {
                sb.append("from ").append(filter.getMinCapacityKg());
            }
            if (filter.getMaxCapacityKg() != null) {
                sb.append(" to ").append(filter.getMaxCapacityKg());
            }
        }
        return sb.toString();
    }
}
