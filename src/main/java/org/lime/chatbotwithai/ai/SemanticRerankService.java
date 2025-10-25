package org.lime.chatbotwithai.ai;

import org.lime.chatbotwithai.product.Product;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SemanticRerankService {

    private final EmbeddingModel embeddingModel;
    private final Map<Long, float[]> productEmbeddingCache = new ConcurrentHashMap<>();

    public SemanticRerankService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public List<Product> rerank(String userQuery, List<Product> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        float[] queryVec = embeddingModel.embed(userQuery);

        record Scored(Product p, double score) {
        }

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (var product : candidates) {
            float[] vec = embeddingForProduct(product);
            double sim = cosine(queryVec, vec);
            scored.add(new Scored(product, sim));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        int limit = Math.min(topK, scored.size());
        List<Product> ranked = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            ranked.add(scored.get(i).p());
        }
        return ranked;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            float x = a[i];
            float y = b[i];
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }

    private float[] embeddingForProduct(Product product) {
        Long id = product.getId();
        if (id == null) {
            return embeddingModel.embed(productText(product));
        }
        return productEmbeddingCache.computeIfAbsent(id, key -> embeddingModel.embed(productText(product)));
    }

    private static String productText(Product product) {
        StringBuilder sb = new StringBuilder();
        appendToken(sb, product.getBrand());
        appendToken(sb, product.getModel());
        appendToken(sb, product.getType());
        appendToken(sb, product.getDescription());
        return sb.toString().trim();
    }

    private static void appendToken(StringBuilder sb, String token) {
        if (token == null) {
            return;
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(trimmed);
    }
}
