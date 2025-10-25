package org.lime.chatbotwithai.product;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Caches distinct product brands to avoid repeated database lookups across services.
 */
@Component
public class BrandCatalog {

    private final ProductRepository repository;
    private final AtomicReference<List<String>> cache = new AtomicReference<>();

    public BrandCatalog(ProductRepository repository) {
        this.repository = repository;
    }

    public List<String> getBrands() {
        List<String> cached = cache.get();
        if (cached != null) {
            return cached;
        }
        List<String> loaded = repository.findDistinctBrandNames().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
        List<String> immutable = List.copyOf(loaded);
        if (cache.compareAndSet(null, immutable)) {
            return immutable;
        }
        return cache.get();
    }

    public void evict() {
        cache.set(null);
    }
}
