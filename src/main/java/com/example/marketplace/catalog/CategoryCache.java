package com.example.marketplace.catalog;

import com.example.marketplace.shared.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class CategoryCache {
    private final Store s;
    private final StringRedisTemplate redis;
    private final boolean enabled;

    public CategoryCache(
            Store s,
            StringRedisTemplate redis,
            @Value("${marketplace.cache-enabled:false}") boolean enabled) {
        this.s = s;
        this.redis = redis;
        this.enabled = enabled;
    }

    public Object categories() {
        if (enabled)
            try {
                String cached = redis.opsForValue().get("marketplace:categories:v1");
                if (cached != null) return s.json.readTree(cached);
            } catch (Exception ignored) {
            }
        var rows = s.db.queryForList("select * from categories order by name");
        if (enabled)
            try {
                redis.opsForValue().set("marketplace:categories:v1", s.json(rows), Duration.ofSeconds(60));
            } catch (Exception ignored) {
            }
        return rows;
    }
}
