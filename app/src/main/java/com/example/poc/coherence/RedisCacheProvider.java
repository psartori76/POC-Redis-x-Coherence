package com.example.poc.coherence;

import redis.clients.jedis.JedisPooled;

import java.net.URI;
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
     * Remove uma entrada especifica do Redis.
     */
    @Override
    public void evict(long id) {
        jedis.del(key(id));
    }

    /**
     * Limpa apenas as chaves da POC. Em producao, evitar KEYS em bases grandes;
     * para a demonstracao ele simplifica a leitura do codigo.
     */
    @Override
    public void clear() {
        for (String key : jedis.keys(KEY_PREFIX + "*")) {
            jedis.del(key);
        }
    }

    /**
     * Conta as chaves da POC no Redis para alimentar metricas da tela.
     */
    @Override
    public long size() {
        return jedis.keys(KEY_PREFIX + "*").size();
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
}
