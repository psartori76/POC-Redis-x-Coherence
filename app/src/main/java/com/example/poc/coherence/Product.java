package com.example.poc.coherence;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Modelo de dominio usado pela POC.
 *
 * O Redis usa a representacao JSON criada em Json.toCacheJson. O Coherence usa
 * POF, configurado em pof-config.xml, para evitar a serializacao Java padrao no
 * caminho quente da demo. Serializable fica como fallback seguro para execucoes
 * locais ou configuracoes antigas.
 */
public record Product(long id, String name, BigDecimal price, Instant updatedAt) implements Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public String toJson(boolean cacheHit, String cacheBackend) {
        /*
         * A resposta da API inclui cacheHit e cacheBackend para deixar a demo
         * visivel: o cliente enxerga se a leitura veio do cache ou do Oracle, e
         * qual produto de cache estava ativo.
         */
        return "{"
                + "\"id\":" + id
                + ",\"name\":\"" + Json.escape(name) + "\""
                + ",\"price\":" + price
                + ",\"updatedAt\":\"" + updatedAt + "\""
                + ",\"cacheHit\":" + cacheHit
                + ",\"cacheBackend\":\"" + Json.escape(cacheBackend) + "\""
                + "}";
    }
}
