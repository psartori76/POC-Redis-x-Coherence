package com.example.poc.coherence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contadores simples em memoria para a tela da POC.
 *
 * AtomicLong e suficiente aqui porque o HttpServer usa multiplas threads. Os
 * numeros nao sao persistidos; eles servem apenas para demonstrar o efeito de
 * cache hit, cache miss e acesso ao banco durante a sessao atual.
 */
final class Metrics {
    private final ConcurrentMap<String, BackendMetrics> backends = new ConcurrentHashMap<>();

    void productRead(String backend, boolean hit, long cacheReadNanos, long dbReadNanos,
                     long cacheWriteNanos, long responseNanos) {
        backend(backend).productRead(hit, cacheReadNanos, dbReadNanos, cacheWriteNanos, responseNanos);
    }

    void productWrite(String backend, long dbWriteNanos, long cacheWriteNanos, long responseNanos) {
        backend(backend).productWrite(dbWriteNanos, cacheWriteNanos, responseNanos);
    }

    void invalidation(String backend) {
        backend(backend).invalidation();
    }

    void error(String backend, long responseNanos) {
        backend(backend).error(responseNanos);
    }

    void reset(String backend) {
        backends.put(backend, new BackendMetrics());
    }

    String toJson(Map<String, Long> cacheSizes, int clusterSize, String nodeName, String cacheBackend) {
        /*
         * Retorna um JSON pequeno consumido pela tela. O backend ativo aparece
         * junto das metricas para reforcar qual produto esta atendendo a app.
         */
        Map<String, BackendSnapshot> snapshots = new LinkedHashMap<>();
        for (String backend : cacheSizes.keySet()) {
            snapshots.put(backend, backend(backend).snapshot(cacheSizes.get(backend)));
        }
        for (String backend : backends.keySet()) {
            snapshots.putIfAbsent(backend, backend(backend).snapshot(-1));
        }

        long hits = snapshots.values().stream().mapToLong(BackendSnapshot::hits).sum();
        long misses = snapshots.values().stream().mapToLong(BackendSnapshot::misses).sum();
        long dbReads = snapshots.values().stream().mapToLong(BackendSnapshot::dbReads).sum();
        long dbWrites = snapshots.values().stream().mapToLong(BackendSnapshot::dbWrites).sum();
        long invalidations = snapshots.values().stream().mapToLong(BackendSnapshot::invalidations).sum();
        long activeCacheSize = snapshots.containsKey(cacheBackend) ? snapshots.get(cacheBackend).cacheSize() : -1;

        StringBuilder backendJson = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, BackendSnapshot> entry : snapshots.entrySet()) {
            if (!first) {
                backendJson.append(',');
            }
            first = false;
            backendJson.append('"').append(Json.escape(entry.getKey())).append("\":")
                    .append(entry.getValue().toJson());
        }

        return "{"
                + "\"node\":\"" + Json.escape(nodeName) + "\""
                + ",\"cacheBackend\":\"" + Json.escape(cacheBackend) + "\""
                + ",\"clusterSize\":" + clusterSize
                + ",\"cacheSize\":" + activeCacheSize
                + ",\"hits\":" + hits
                + ",\"misses\":" + misses
                + ",\"dbReads\":" + dbReads
                + ",\"dbWrites\":" + dbWrites
                + ",\"invalidations\":" + invalidations
                + ",\"backends\":{" + backendJson + "}"
                + "}";
    }

    private BackendMetrics backend(String backend) {
        return backends.computeIfAbsent(backend, ignored -> new BackendMetrics());
    }

    private static double millis(long nanos) {
        return Math.round((nanos / 1_000_000.0) * 100.0) / 100.0;
    }

    private static double pct(long numerator, long denominator) {
        if (denominator == 0) {
            return 0;
        }
        return round2((numerator * 100.0) / denominator);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class BackendMetrics {
        private final AtomicLong requests = new AtomicLong();
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private final AtomicLong cacheReads = new AtomicLong();
        private final AtomicLong cacheWrites = new AtomicLong();
        private final AtomicLong dbReads = new AtomicLong();
        private final AtomicLong dbWrites = new AtomicLong();
        private final AtomicLong invalidations = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();
        private final AtomicLong cacheReadNanosTotal = new AtomicLong();
        private final AtomicLong cacheReadNanosLast = new AtomicLong();
        private final AtomicLong responseNanosTotal = new AtomicLong();
        private final AtomicLong responseNanosLast = new AtomicLong();
        private final AtomicLong dbNanosTotal = new AtomicLong();
        private final AtomicLong dbNanosLast = new AtomicLong();
        private final AtomicLong hitCacheReadNanosTotal = new AtomicLong();
        private final AtomicLong hitCacheReadNanosLast = new AtomicLong();
        private final AtomicLong missCacheReadNanosTotal = new AtomicLong();
        private final AtomicLong missCacheReadNanosLast = new AtomicLong();
        private final AtomicLong hitResponseNanosTotal = new AtomicLong();
        private final AtomicLong hitResponseNanosLast = new AtomicLong();
        private final AtomicLong missResponseNanosTotal = new AtomicLong();
        private final AtomicLong missResponseNanosLast = new AtomicLong();

        void productRead(boolean hit, long cacheReadNanos, long dbReadNanos, long cacheWriteNanos,
                         long responseNanos) {
            requests.incrementAndGet();
            cacheReads.incrementAndGet();
            cacheReadNanosTotal.addAndGet(cacheReadNanos);
            cacheReadNanosLast.set(cacheReadNanos);
            responseNanosTotal.addAndGet(responseNanos);
            responseNanosLast.set(responseNanos);
            if (hit) {
                hits.incrementAndGet();
                hitCacheReadNanosTotal.addAndGet(cacheReadNanos);
                hitCacheReadNanosLast.set(cacheReadNanos);
                hitResponseNanosTotal.addAndGet(responseNanos);
                hitResponseNanosLast.set(responseNanos);
            } else {
                misses.incrementAndGet();
                missCacheReadNanosTotal.addAndGet(cacheReadNanos);
                missCacheReadNanosLast.set(cacheReadNanos);
                missResponseNanosTotal.addAndGet(responseNanos);
                missResponseNanosLast.set(responseNanos);
            }
            if (dbReadNanos >= 0) {
                dbReads.incrementAndGet();
                dbNanosTotal.addAndGet(dbReadNanos);
                dbNanosLast.set(dbReadNanos);
            }
            if (cacheWriteNanos >= 0) {
                cacheWrites.incrementAndGet();
            }
        }

        void productWrite(long dbWriteNanos, long cacheWriteNanos, long responseNanos) {
            requests.incrementAndGet();
            dbWrites.incrementAndGet();
            cacheWrites.incrementAndGet();
            dbNanosTotal.addAndGet(dbWriteNanos);
            dbNanosLast.set(dbWriteNanos);
            cacheReadNanosLast.set(cacheWriteNanos);
            responseNanosTotal.addAndGet(responseNanos);
            responseNanosLast.set(responseNanos);
        }

        void invalidation() {
            invalidations.incrementAndGet();
        }

        void error(long responseNanos) {
            requests.incrementAndGet();
            errors.incrementAndGet();
            responseNanosTotal.addAndGet(responseNanos);
            responseNanosLast.set(responseNanos);
        }

        BackendSnapshot snapshot(long cacheSize) {
            long requestCount = requests.get();
            long cacheReadCount = cacheReads.get();
            long hitCount = hits.get();
            long missCount = misses.get();
            long readCount = hitCount + missCount;
            long dbCount = dbReads.get() + dbWrites.get();
            long readCacheNanosTotal = hitCacheReadNanosTotal.get() + missCacheReadNanosTotal.get();
            long readResponseNanosTotal = hitResponseNanosTotal.get() + missResponseNanosTotal.get();
            double avgHitResponseMs = hitCount == 0 ? 0 : millis(hitResponseNanosTotal.get() / hitCount);
            double avgMissResponseMs = missCount == 0 ? 0 : millis(missResponseNanosTotal.get() / missCount);
            double responseGainMs = avgHitResponseMs == 0 || avgMissResponseMs == 0
                    ? 0
                    : round2(avgMissResponseMs - avgHitResponseMs);
            double speedGainPct = avgHitResponseMs == 0 || avgMissResponseMs == 0
                    ? 0
                    : round2(clamp(((avgMissResponseMs - avgHitResponseMs) * 100.0) / avgMissResponseMs, 0, 100));
            double hitRatePct = pct(hitCount, readCount);
            double errorRatePct = pct(errors.get(), requestCount);
            /*
             * Indicador composto para a demo: quando ha dados de hit e miss,
             * combina taxa de hit, ganho de tempo observado e estabilidade. Se
             * ainda nao ha comparacao de tempos, usa taxa de hit e ausencia de
             * erros para evitar um score artificialmente alto.
             */
            double efficiencyScorePct = readCount == 0
                    ? 0
                    : (speedGainPct > 0
                            ? round2((hitRatePct * 0.65) + (speedGainPct * 0.25) + ((100 - errorRatePct) * 0.10))
                            : round2((hitRatePct * 0.85) + ((100 - errorRatePct) * 0.15)));
            return new BackendSnapshot(
                    cacheSize,
                    requestCount,
                    hitCount,
                    missCount,
                    cacheReadCount,
                    cacheWrites.get(),
                    dbReads.get(),
                    dbWrites.get(),
                    invalidations.get(),
                    errors.get(),
                    readCount,
                    pct(hitCount, readCount),
                    pct(missCount, readCount),
                    pct(dbReads.get(), readCount),
                    errorRatePct,
                    millis(cacheReadNanosLast.get()),
                    cacheReadCount == 0 ? 0 : millis(cacheReadNanosTotal.get() / cacheReadCount),
                    readCount == 0 ? 0 : millis(readCacheNanosTotal / readCount),
                    millis(hitCacheReadNanosLast.get()),
                    hitCount == 0 ? 0 : millis(hitCacheReadNanosTotal.get() / hitCount),
                    millis(missCacheReadNanosLast.get()),
                    missCount == 0 ? 0 : millis(missCacheReadNanosTotal.get() / missCount),
                    millis(responseNanosLast.get()),
                    requestCount == 0 ? 0 : millis(responseNanosTotal.get() / requestCount),
                    readCount == 0 ? 0 : millis(readResponseNanosTotal / readCount),
                    millis(hitResponseNanosLast.get()),
                    avgHitResponseMs,
                    millis(missResponseNanosLast.get()),
                    avgMissResponseMs,
                    millis(dbNanosLast.get()),
                    dbCount == 0 ? 0 : millis(dbNanosTotal.get() / dbCount),
                    hitCount,
                    responseGainMs,
                    speedGainPct,
                    efficiencyScorePct
            );
        }
    }

    private record BackendSnapshot(long cacheSize, long requests, long hits, long misses, long cacheReads,
                                   long cacheWrites, long dbReads, long dbWrites, long invalidations,
                                   long errors, long totalReads, double hitRatePct, double missRatePct,
                                   double dbReadRatePct, double errorRatePct, double lastCacheMs,
                                   double avgCacheMs, double avgReadCacheMs, double lastHitCacheMs, double avgHitCacheMs,
                                   double lastMissCacheMs, double avgMissCacheMs, double lastResponseMs,
                                   double avgResponseMs, double avgReadResponseMs, double lastHitResponseMs,
                                   double avgHitResponseMs, double lastMissResponseMs, double avgMissResponseMs,
                                   double lastDbMs, double avgDbMs, long estimatedDbAvoided,
                                   double responseGainMs, double speedGainPct, double efficiencyScorePct) {
        String toJson() {
            return "{"
                    + "\"cacheSize\":" + cacheSize
                    + ",\"requests\":" + requests
                    + ",\"hits\":" + hits
                    + ",\"misses\":" + misses
                    + ",\"totalReads\":" + totalReads
                    + ",\"cacheReads\":" + cacheReads
                    + ",\"cacheWrites\":" + cacheWrites
                    + ",\"dbReads\":" + dbReads
                    + ",\"dbWrites\":" + dbWrites
                    + ",\"invalidations\":" + invalidations
                    + ",\"errors\":" + errors
                    + ",\"hitRatePct\":" + hitRatePct
                    + ",\"missRatePct\":" + missRatePct
                    + ",\"dbReadRatePct\":" + dbReadRatePct
                    + ",\"errorRatePct\":" + errorRatePct
                    + ",\"lastCacheMs\":" + lastCacheMs
                    + ",\"avgCacheMs\":" + avgCacheMs
                    + ",\"avgReadCacheMs\":" + avgReadCacheMs
                    + ",\"lastHitCacheMs\":" + lastHitCacheMs
                    + ",\"avgHitCacheMs\":" + avgHitCacheMs
                    + ",\"lastMissCacheMs\":" + lastMissCacheMs
                    + ",\"avgMissCacheMs\":" + avgMissCacheMs
                    + ",\"lastResponseMs\":" + lastResponseMs
                    + ",\"avgResponseMs\":" + avgResponseMs
                    + ",\"avgReadResponseMs\":" + avgReadResponseMs
                    + ",\"lastHitResponseMs\":" + lastHitResponseMs
                    + ",\"avgHitResponseMs\":" + avgHitResponseMs
                    + ",\"lastMissResponseMs\":" + lastMissResponseMs
                    + ",\"avgMissResponseMs\":" + avgMissResponseMs
                    + ",\"lastDbMs\":" + lastDbMs
                    + ",\"avgDbMs\":" + avgDbMs
                    + ",\"estimatedDbAvoided\":" + estimatedDbAvoided
                    + ",\"responseGainMs\":" + responseGainMs
                    + ",\"speedGainPct\":" + speedGainPct
                    + ",\"efficiencyScorePct\":" + efficiencyScorePct
                    + "}";
        }
    }
}
