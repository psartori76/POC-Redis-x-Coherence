package com.example.poc.coherence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Implementa a "magica" da demonstracao.
 *
 * Este provider tambem implementa CacheProvider, entao a aplicacao continua
 * chamando get/put/evict como se existisse um unico cache. Internamente ele
 * mantem uma lista de providers reais e encaminha cada chamada para o backend
 * que estiver marcado como ativo.
 */
final class SwitchingCacheProvider implements CacheProvider {
    /*
     * LinkedHashMap preserva a ordem em que os backends foram registrados. Isso
     * deixa a resposta JSON estavel e facilita a leitura na tela da demo.
     */
    private final Map<String, CacheProvider> providers;

    /*
     * AtomicReference permite trocar o backend ativo em tempo de execucao sem
     * reiniciar o servico e sem criar uma condicao de corrida simples entre
     * threads HTTP diferentes.
     */
    private final AtomicReference<String> activeName;

    SwitchingCacheProvider(String activeName, CacheProvider... providers) {
        this.providers = new LinkedHashMap<>();
        for (CacheProvider provider : providers) {
            this.providers.put(provider.name(), provider);
        }
        if (!this.providers.containsKey(activeName)) {
            throw new IllegalArgumentException("Unknown cache backend: " + activeName);
        }
        this.activeName = new AtomicReference<>(activeName);
    }

    @Override
    public String name() {
        return activeName.get();
    }

    /*
     * A partir daqui todos os metodos seguem o mesmo padrao: resolver o backend
     * ativo e delegar a operacao. A app nao precisa de if/else para Redis ou
     * Coherence em cada ponto do codigo de negocio.
     */
    @Override
    public Optional<Product> get(long id) throws Exception {
        return active().get(id);
    }

    @Override
    public void put(Product product) throws Exception {
        active().put(product);
    }

    @Override
    public void evict(long id) throws Exception {
        active().evict(id);
    }

    @Override
    public void clear() throws Exception {
        active().clear();
    }

    @Override
    public long size() throws Exception {
        return active().size();
    }

    @Override
    public boolean ping() {
        return active().ping();
    }

    CacheProvider activeProvider() {
        return active();
    }

    CacheProvider provider(String name) {
        CacheProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown cache backend: " + name);
        }
        return provider;
    }

    /**
     * Troca o backend ativo. A API chama este metodo quando recebe:
     * POST /cache/backend/redis ou POST /cache/backend/coherence.
     */
    void switchTo(String name) {
        if (!providers.containsKey(name)) {
            throw new IllegalArgumentException("Unknown cache backend: " + name);
        }
        activeName.set(name);
    }

    String switchToNext() {
        String current = name();
        for (String backend : providers.keySet()) {
            if (!backend.equals(current)) {
                switchTo(backend);
                return backend;
            }
        }
        return current;
    }

    Map<String, Long> sizes() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (CacheProvider provider : providers.values()) {
            try {
                result.put(provider.name(), provider.size());
            } catch (Exception e) {
                result.put(provider.name(), -1L);
            }
        }
        return result;
    }

    /**
     * Monta uma resposta simples para a tela mostrar qual backend esta ativo e
     * se cada produto esta respondendo.
     */
    String statusJson() {
        String backends = providers.values().stream()
                .map(provider -> "{\"name\":\"" + Json.escape(provider.name()) + "\""
                        + ",\"active\":" + provider.name().equals(name())
                        + ",\"up\":" + provider.ping()
                        + "}")
                .collect(Collectors.joining(","));
        return "{\"active\":\"" + Json.escape(name()) + "\",\"backends\":[" + backends + "]}";
    }

    /**
     * Fecha todos os providers reais quando a app for encerrada.
     */
    @Override
    public void close() throws Exception {
        for (CacheProvider provider : providers.values()) {
            provider.close();
        }
    }

    /**
     * Resolve o provider real que vai receber a proxima chamada.
     */
    private CacheProvider active() {
        return providers.get(activeName.get());
    }
}
