package com.aiworkbench.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 极简 TTL 缓存(无外部依赖):按 key 存值并记录写入时间,超时后失效重建。
 * 仅用于低频变化的读聚合(如仪表盘统计),避免每个请求都重算数据库聚合。
 */
public final class TtlCache<K, V> {

    private final long ttlMillis;
    private final Map<K, Entry<V>> store = new ConcurrentHashMap<>();

    public TtlCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    /** 取缓存;缺失或过期返回 null(由调用方重建后 put)。 */
    public V get(K key) {
        Entry<V> e = store.get(key);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.createdAt > ttlMillis) {
            store.remove(key, e);
            return null;
        }
        return e.value;
    }

    public void put(K key, V value) {
        if (value != null) {
            store.put(key, new Entry<>(value, System.currentTimeMillis()));
        }
    }

    public void invalidate(K key) {
        store.remove(key);
    }

    public void clear() {
        store.clear();
    }

    private record Entry<V>(V value, long createdAt) {}
}
