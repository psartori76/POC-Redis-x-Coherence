package com.example.poc.coherence;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Adaptador que transforma Redis em um CacheProvider.
 *
 * O resto da aplicacao nao enxerga Jedis, comandos Redis ou formato de chave.
 * Esses detalhes ficam isolados aqui.
 */
final class RedisCacheProvider implements CacheProvider {
    /*
     * Prefixo usado para separar as chaves desta POC de qualquer outro dado que
     * eventualmente exista no mesmo Redis.
     */
    private static final String KEY_PREFIX = "products:";
    private static final int PIPELINE_BATCH_SIZE = 1000;
    private static final int SCAN_COUNT = 1000;

    private final JedisPooled jedis;
    private final int ttlSeconds;

    RedisCacheProvider(String redisUrl, int ttlSeconds) {
        this.jedis = new JedisPooled(URI.create(redisUrl));
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public String name() {
        return "redis";
    }

    /**
     * Redis armazena o produto como JSON em uma chave texto products:<id>.
     */
    @Override
    public Optional<Product> get(long id) {
        String value = jedis.get(key(id));
        return value == null ? Optional.empty() : Optional.of(Json.parseCachedProduct(value));
    }

    /**
     * setex grava o valor ja com expiracao. Assim a POC mostra um comportamento
     * comum de cache: se o dado envelhecer, ele sai sozinho.
     */
    @Override
    public void put(Product product) {
        jedis.setex(key(product.id()), ttlSeconds, Json.toCacheJson(product));
    }

    /**
     * Usa pipeline para reduzir round-trips no warm-up. Cada lote e sincronizado
     * periodicamente para evitar uma fila grande demais no cliente.
     */
    @Override
    public void putAll(Collection<Product> products) {
        try (Pipeline pipeline = jedis.pipelined()) {
            int pending = 0;
            for (Product product : products) {
                pipeline.setex(key(product.id()), ttlSeconds, Json.toCacheJson(product));
                pending++;
                if (pending % PIPELINE_BATCH_SIZE == 0) {
                    pipeline.sync();
                }
            }
            pipeline.sync();
        }
    }

    /**
     * Remove uma entrada especifica do Redis.
     */
    @Override
    public void evict(long id) {
        jedis.del(key(id));
    }

    /**
     * Limpa apenas as chaves da POC. SCAN evita bloquear o Redis como KEYS pode
     * fazer quando ha muitos registros no cache.
     */
    @Override
    public void clear() {
        scanKeys(keys -> jedis.unlink(keys.toArray(String[]::new)));
    }

    /**
     * Conta as chaves da POC no Redis para alimentar metricas da tela.
     */
    @Override
    public long size() {
        long[] count = {0};
        scanKeys(keys -> count[0] += keys.size());
        return count[0];
    }

    /**
     * Usa PING para saber se o Redis esta acessivel.
     */
    @Override
    public boolean ping() {
        try {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Fecha o pool/conexao gerenciado pelo Jedis.
     */
    @Override
    public void close() {
        jedis.close();
    }

    /**
     * Centraliza a convencao de chave em um unico lugar.
     */
    private String key(long id) {
        return KEY_PREFIX + id;
    }

    private void scanKeys(KeyBatchConsumer consumer) {
        ScanParams params = new ScanParams()
                .match(KEY_PREFIX + "*")
                .count(SCAN_COUNT);
        String cursor = ScanParams.SCAN_POINTER_START;
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            List<String> keys = result.getResult();
            if (!keys.isEmpty()) {
                consumer.accept(keys);
            }
            cursor = result.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
    }

    @FunctionalInterface
    private interface KeyBatchConsumer {
        void accept(List<String> keys);
    }
}
