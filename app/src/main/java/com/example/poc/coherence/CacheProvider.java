package com.example.poc.coherence;

import java.util.Collection;
import java.util.Optional;

/**
 * Contrato unico de cache usado pela aplicacao.
 *
 * A regra de negocio nao precisa saber se o dado esta no Redis ou no Coherence.
 * Ela conhece apenas estas operacoes basicas: ler, gravar, remover, limpar,
 * medir tamanho e verificar saude. Cada produto de cache implementa esse mesmo
 * contrato do seu jeito.
 */
interface CacheProvider extends AutoCloseable {
    /**
     * Nome logico do backend, usado pela API e pela tela para mostrar/trocar o
     * cache ativo. Exemplo: "redis" ou "coherence".
     */
    String name();

    /**
     * Busca um produto no cache. Optional.empty() significa cache miss: a app
     * deve ir ao Oracle Database, que e a fonte oficial dos dados.
     */
    Optional<Product> get(long id) throws Exception;

    /**
     * Grava ou atualiza o produto no cache ativo depois de uma leitura no banco
     * ou de uma alteracao feita pela propria aplicacao.
     */
    void put(Product product) throws Exception;

    /**
     * Grava varios produtos em lote. A implementacao padrao mantem o contrato
     * simples, mas providers reais podem sobrescrever para usar putAll,
     * pipeline ou outro mecanismo nativo mais eficiente.
     */
    default void putAll(Collection<Product> products) throws Exception {
        for (Product product : products) {
            put(product);
        }
    }

    /**
     * Remove apenas uma chave do cache ativo. Isso simula invalidacao pontual.
     */
    void evict(long id) throws Exception;

    /**
     * Limpa todas as entradas conhecidas por este backend de cache.
     */
    void clear() throws Exception;

    /**
     * Retorna o tamanho do cache ativo para fins de observabilidade da POC.
     */
    long size() throws Exception;

    /**
     * Verifica se o backend esta respondendo sem derrubar a requisicao principal.
     */
    boolean ping();

    @Override
    default void close() throws Exception {
    }
}
