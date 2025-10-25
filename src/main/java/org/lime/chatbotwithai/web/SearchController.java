package org.lime.chatbotwithai.web;

import org.lime.chatbotwithai.ai.*;
import org.lime.chatbotwithai.product.*;
import lombok.Data;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static org.lime.chatbotwithai.product.ProductSpec.*;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final QueryExtractionService extractor;
    private final SemanticRerankService reranker;
    private final SearchAnswerService answer;
    private final ProductRepository repo;

    public SearchController(QueryExtractionService extractor,
                            SemanticRerankService reranker,
                            SearchAnswerService answer,
                            ProductRepository repo) {
        this.extractor = extractor;
        this.reranker = reranker;
        this.answer = answer;
        this.repo = repo;
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam("q") String q,
                                 @RequestParam(value="k", defaultValue="5") int k) {

        // 1) Use the LLM to map natural language into structured filters.
        QueryFilter filter = extractor.extract(q);

        // 2) Apply structured filtering first, then semantic re-ranking.
        Specification<Product> spec = Specification
                .anyOf(brandEquals(filter.getBrand()))
                .and(typeEquals(filter.getType()))
                .and(priceBetween(filter.getMinPrice(), filter.getMaxPrice()));

        Specification<Product> dimensionSpec =
                dimensionsCloseTo(filter.getWidthCm(), filter.getHeightCm(), filter.getDepthCm(), 1.0);
        if (dimensionSpec != null) {
            spec = spec.and(dimensionSpec);
        }
        var filtered = repo.findAll(spec);

        var top = reranker.rerank(q, filtered.isEmpty()? repo.findAll() : filtered, k);

        // 3) Produce the natural-language explanation and validation.
        String explanation = answer.explain(q, filter, top);

        // Return blog-friendly JSON for inspection.
        var resp = new SearchResponse();
        resp.query = q;
        resp.filter = filter;
        resp.sizeBeforeRerank = filtered.size();
        resp.results = top;
        resp.explanation = explanation;
        return resp;
    }

    @Data
    public static class SearchResponse {
        public String query;
        public QueryFilter filter;
        public int sizeBeforeRerank;
        public List<Product> results;
        public String explanation;
    }
}
