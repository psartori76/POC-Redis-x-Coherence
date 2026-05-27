package com.example.poc.coherence;

import com.tangosol.net.NamedMap;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Adaptador que transforma um NamedMap do Coherence em um CacheProvider.
 *
 * No Coherence, o cache distribuido e exposto como um mapa nomeado. Para a
 * aplicacao, isso vira as mesmas operacoes do contrato comum: get, put, remove,
 * clear, size e ping.
 */
final class CoherenceCacheProvider implements CacheProvider {
    /*
     * NamedMap e a abstracao principal usada pela app para conversar com o
     * cluster Coherence. A distribuicao, particionamento e storage ficam do
     * lado do Coherence.
     */
    private final NamedMap<Long, Product> products;

    CoherenceCacheProvider(NamedMap<Long, Product> products) {
        this.products = products;
    }

    @Override
    public String name() {
        return "coherence";
    }

    /**
     * Busca diretamente no mapa distribuido do Coherence.
     */
    @Override
    public Optional<Product> get(long id) {
        return Optional.ofNullable(products.get(id));
    }

    /**
     * Grava o produto no mapa distribuido. O Coherence cuida da propagacao no
     * cluster conforme a configuracao do cache.
     */
    @Override
    public void put(Product product) {
        products.put(product.id(), product);
    }

    /**
     * Usa putAll para reduzir chamadas remotas durante warm-up. Com near-cache,
     * essa carga tambem ajuda a deixar o front cache da app preenchido para a
     * demonstracao logo apos o aquecimento.
     */
    @Override
    public void putAll(Collection<Product> productBatch) {
        Map<Long, Product> batch = new LinkedHashMap<>(productBatch.size());
        for (Product product : productBatch) {
            batch.put(product.id(), product);
        }
        products.putAll(batch);
    }

    /**
     * Remove uma entrada especifica do mapa distribuido.
     */
    @Override
    public void evict(long id) {
        products.remove(id);
    }

    /**
     * Limpa o cache Coherence usado pela POC.
     */
    @Override
    public void clear() {
        products.clear();
    }

    /**
     * Mede quantas entradas existem no NamedMap.
     */
    @Override
    public long size() {
        return products.size();
    }

    /**
     * Faz uma operacao leve no mapa para confirmar se o cluster responde.
     */
    @Override
    public boolean ping() {
        try {
            products.size();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
