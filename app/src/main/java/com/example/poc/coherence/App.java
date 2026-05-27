package com.example.poc.coherence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tangosol.net.CacheFactory;
import com.tangosol.net.Coherence;
import com.tangosol.net.NamedMap;
import com.tangosol.net.Session;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * Aplicacao HTTP simples usada na POC.
 *
 * O mesmo JAR pode subir em dois modos:
 * - APP_MODE=app: inicia a API, a tela e o CacheProvider que alterna Redis/Coherence.
 * - APP_MODE=coherence-node: entra no cluster apenas como no de storage Coherence.
 *
 * Essa escolha deixa a demo pequena: um artefato Java, dois papeis de execucao.
 */
public final class App {
    /*
     * A aplicacao depende do SwitchingCacheProvider, e nao diretamente de Redis
     * ou Coherence. Esse e o ponto que torna a troca transparente para o fluxo
     * de produto.
     */
    private final SwitchingCacheProvider cacheProvider;

    /*
     * O Oracle Database permanece como fonte oficial. Cache miss sempre volta
     * para o repository antes de popular o cache ativo.
     */
    private final ProductRepository repository;

    /*
     * Metricas em memoria para a tela mostrar cache hits, misses e leituras no DB.
     */
    private final Metrics metrics;
    private final String nodeName;

    /*
     * URL interna do endpoint REST de management do Coherence. A app usa isso
     * para publicar um proxy local na tela, sem expor a porta 30000 na internet.
     */
    private final String managementBaseUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private App(SwitchingCacheProvider cacheProvider, ProductRepository repository, Metrics metrics, String nodeName,
                String managementBaseUrl) {
        this.cacheProvider = cacheProvider;
        this.repository = repository;
        this.metrics = metrics;
        this.nodeName = nodeName;
        this.managementBaseUrl = managementBaseUrl;
    }

    public static void main(String[] args) throws Exception {
        /*
         * O cache-config define o NamedMap "products"; o override define como o
         * membro encontra o cluster por WKA.
         */
        System.setProperty("coherence.cacheconfig", "coherence-cache-config.xml");
        System.setProperty("coherence.pof.enabled", "true");
        System.setProperty("coherence.pof.config", "pof-config.xml");

        String appMode = env("APP_MODE", "app");
        String nodeName = env("NODE_NAME", java.net.InetAddress.getLocalHost().getHostName());

        /*
         * Tanto a VM da app quanto a VM de storage entram no mesmo cluster. A
         * diferenca real vem dos system properties do systemd:
         * - app-bastion roda com localstorage=false
         * - coherence-node roda com storage habilitado e management HTTP
         */
        Coherence coherence = Coherence.clusterMember();
        coherence.start().join();

        /*
         * Em modo coherence-node, este processo nao abre API HTTP da POC. Ele
         * apenas fica vivo como membro de storage do Coherence.
         */
        if ("coherence-node".equalsIgnoreCase(appMode)) {
            System.out.printf("Coherence storage node running as %s%n", nodeName);
            Thread.currentThread().join();
            return;
        }

        String dbUrl = env("DB_URL", "jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1");
        String dbUser = env("DB_USER", "COHDEMO");
        String dbPassword = env("DB_PASSWORD", "");
        int port = Integer.parseInt(env("HTTP_PORT", "8080"));
        String redisUrl = env("REDIS_URL", "redis://127.0.0.1:6379");
        int cacheTtlSeconds = Integer.parseInt(env("CACHE_TTL_SECONDS", "3600"));
        String activeCache = env("ACTIVE_CACHE_BACKEND", "redis");
        String managementBaseUrl = env("COHERENCE_MANAGEMENT_URL", "http://127.0.0.1:30000/management/coherence");

        if (dbPassword.isBlank()) {
            throw new IllegalStateException("DB_PASSWORD environment variable is required.");
        }

        /*
         * A app tambem participa do cluster para acessar o NamedMap. Por estar
         * com localstorage=false no systemd, ela atua como cliente/membro sem
         * armazenar particoes localmente.
         */
        Session session = coherence.getSession();
        NamedMap<Long, Product> products = session.getMap("products");

        /*
         * Aqui os dois produtos sao registrados atras do contrato comum. O valor
         * ACTIVE_CACHE_BACKEND define quem atende primeiro quando a app sobe.
         */
        SwitchingCacheProvider cacheProvider = new SwitchingCacheProvider(
                activeCache,
                new RedisCacheProvider(redisUrl, cacheTtlSeconds),
                new CoherenceCacheProvider(products)
        );

        App app = new App(cacheProvider, new ProductRepository(dbUrl, dbUser, dbPassword), new Metrics(), nodeName,
                managementBaseUrl);
        app.start(port);
    }

    private void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        /*
         * Endpoints de demo. A UI chama essas APIs para ler produtos, limpar o
         * cache, trocar backend e consultar o management do Coherence.
         */
        server.createContext("/", this::root);
        server.createContext("/console", this::console);
        server.createContext("/management-proxy", this::managementProxy);
        server.createContext("/health", this::health);
        server.createContext("/products", this::products);
        server.createContext("/cache/products", this::invalidateProduct);
        server.createContext("/cache/clear", this::clearCache);
        server.createContext("/cache/warm", this::warmCache);
        server.createContext("/cache/backend", this::cacheBackend);
        server.createContext("/metrics/reset", this::resetMetrics);
        server.createContext("/metrics", this::metrics);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.printf("Coherence cache demo listening on %d as %s%n", port, nodeName);
    }

    private void root(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            respond(exchange, 404, error("Not found."));
            return;
        }
        redirect(exchange, "/console");
    }

    private void console(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, error("Method not allowed."));
            return;
        }
        respond(exchange, 200, "text/html; charset=utf-8", CONSOLE_HTML);
    }

    private void health(HttpExchange exchange) throws IOException {
        /*
         * Health check consolidado: confirma DB, cache ativo e tamanho do
         * cluster Coherence em uma unica resposta para a demo.
         */
        respond(exchange, 200, "{"
                + "\"status\":\"UP\""
                + ",\"node\":\"" + Json.escape(nodeName) + "\""
                + ",\"db\":" + repository.ping()
                + ",\"cacheBackend\":\"" + Json.escape(cacheProvider.name()) + "\""
                + ",\"cacheUp\":" + cacheProvider.ping()
                + ",\"clusterSize\":" + clusterSize()
                + ",\"cacheSize\":" + cacheSize()
                + "}");
    }

    private void products(HttpExchange exchange) throws IOException {
        try {
            /*
             * /products/{id} e o endpoint principal da POC. GET exercita o
             * padrao cache-aside; PUT atualiza Oracle e repopula o cache ativo.
             */
            long id = idFromPath(exchange, "/products/");
            if ("GET".equals(exchange.getRequestMethod())) {
                getProduct(exchange, id);
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                putProduct(exchange, id);
            } else {
                respond(exchange, 405, error("Method not allowed."));
            }
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            respond(exchange, 500, error(e.getMessage()));
        }
    }

    private void getProduct(HttpExchange exchange, long id) throws Exception {
        /*
         * Padrao cache-aside:
         * 1. tenta ler no cache ativo;
         * 2. se encontrou, responde cacheHit=true;
         * 3. se nao encontrou, busca no Oracle;
         * 4. grava o resultado no cache ativo;
         * 5. responde cacheHit=false.
         */
        long requestStart = System.nanoTime();
        CacheProvider provider = cacheProvider.activeProvider();
        String backend = provider.name();
        long cacheReadNanos = -1;
        long dbReadNanos = -1;
        long cacheWriteNanos = -1;
        try {
            long cacheStart = System.nanoTime();
            Optional<Product> cached = provider.get(id);
            cacheReadNanos = System.nanoTime() - cacheStart;
            if (cached.isPresent()) {
                metrics.productRead(backend, true, cacheReadNanos, dbReadNanos, cacheWriteNanos,
                        System.nanoTime() - requestStart);
                respond(exchange, 200, cached.get().toJson(true, backend));
                return;
            }

            long dbStart = System.nanoTime();
            Optional<Product> fromDb = repository.findById(id);
            dbReadNanos = System.nanoTime() - dbStart;
            if (fromDb.isEmpty()) {
                metrics.productRead(backend, false, cacheReadNanos, dbReadNanos, cacheWriteNanos,
                        System.nanoTime() - requestStart);
                respond(exchange, 404, error("Product not found."));
                return;
            }

            long cacheWriteStart = System.nanoTime();
            provider.put(fromDb.get());
            cacheWriteNanos = System.nanoTime() - cacheWriteStart;
            metrics.productRead(backend, false, cacheReadNanos, dbReadNanos, cacheWriteNanos,
                    System.nanoTime() - requestStart);
            respond(exchange, 200, fromDb.get().toJson(false, backend));
        } catch (Exception e) {
            metrics.error(backend, System.nanoTime() - requestStart);
            throw e;
        }
    }

    private void putProduct(HttpExchange exchange, long id) throws Exception {
        /*
         * Escrita simples para a demo: Oracle recebe a atualizacao e o cache
         * ativo recebe o novo valor imediatamente.
         */
        long requestStart = System.nanoTime();
        CacheProvider provider = cacheProvider.activeProvider();
        String backend = provider.name();
        Json.ProductInput input = Json.parseProductInput(readBody(exchange));
        try {
            long dbStart = System.nanoTime();
            Product product = repository.upsert(id, input.name(), input.price());
            long dbWriteNanos = System.nanoTime() - dbStart;
            long cacheWriteStart = System.nanoTime();
            provider.put(product);
            long cacheWriteNanos = System.nanoTime() - cacheWriteStart;
            metrics.productWrite(backend, dbWriteNanos, cacheWriteNanos, System.nanoTime() - requestStart);
            respond(exchange, 200, product.toJson(false, backend));
        } catch (Exception e) {
            metrics.error(backend, System.nanoTime() - requestStart);
            throw e;
        }
    }

    private void invalidateProduct(HttpExchange exchange) throws IOException {
        try {
            if (!"DELETE".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, error("Method not allowed."));
                return;
            }
            long id = idFromPath(exchange, "/cache/products/");
            /*
             * A invalidacao atua somente no backend ativo. Isso ajuda a mostrar
             * que Redis e Coherence estao lado a lado, com estados independentes.
             */
            CacheProvider provider = cacheProvider.activeProvider();
            provider.evict(id);
            metrics.invalidation(provider.name());
            respond(exchange, 200, "{\"invalidated\":" + id
                    + ",\"cacheBackend\":\"" + Json.escape(provider.name()) + "\"}");
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 500, error(e.getMessage()));
        }
    }

    private void clearCache(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, error("Method not allowed."));
            return;
        }
        try {
            /*
             * Sem sufixo, limpa o backend ativo. Com /cache/clear/redis ou
             * /cache/clear/coherence, limpa o produto escolhido sem trocar o
             * backend ativo da app.
             */
            String path = exchange.getRequestURI().getPath();
            CacheProvider provider;
            if ("/cache/clear".equals(path)) {
                provider = cacheProvider.activeProvider();
            } else if (path.startsWith("/cache/clear/") && path.length() > "/cache/clear/".length()) {
                provider = cacheProvider.provider(path.substring("/cache/clear/".length()).toLowerCase());
            } else {
                throw new IllegalArgumentException("Missing cache backend.");
            }
            provider.clear();
            metrics.invalidation(provider.name());
            respond(exchange, 200, "{\"cleared\":true"
                    + ",\"cacheBackend\":\"" + Json.escape(provider.name()) + "\"}");
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 500, error(e.getMessage()));
        }
    }

    private void warmCache(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, error("Method not allowed."));
            return;
        }
        try {
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/cache/warm/") || path.length() == "/cache/warm/".length()) {
                throw new IllegalArgumentException("Missing cache backend.");
            }

            String backend = path.substring("/cache/warm/".length()).toLowerCase();
            long startId = Long.parseLong(queryParam(exchange, "start", "1"));
            int total = Integer.parseInt(queryParam(exchange, "total", "50000"));
            Optional<String> percentParam = optionalQueryParam(exchange, "percent");
            int limit;
            double percent;
            if (percentParam.isPresent()) {
                percent = Double.parseDouble(percentParam.get());
                if (percent < 0 || percent > 100) {
                    throw new IllegalArgumentException("Warm-up percent must be between 0 and 100.");
                }
                limit = (int) Math.round(total * (percent / 100.0));
            } else {
                limit = Integer.parseInt(queryParam(exchange, "limit", "25000"));
                percent = total == 0 ? 0 : Math.round(((limit * 100.0) / total) * 100.0) / 100.0;
            }
            boolean clear = Boolean.parseBoolean(queryParam(exchange, "clear", "true"));
            boolean resetStats = Boolean.parseBoolean(queryParam(exchange, "resetStats", "true"));
            if (startId < 1 || total < 1 || total > 100000 || limit < 0 || limit > 100000) {
                throw new IllegalArgumentException("Invalid warm-up range.");
            }

            CacheProvider provider = cacheProvider.provider(backend);
            long started = System.nanoTime();
            if (clear) {
                provider.clear();
            }
            List<Product> products = limit == 0 ? List.of() : repository.findRange(startId, limit);
            provider.putAll(products);
            if (resetStats) {
                metrics.reset(backend);
            }
            double elapsedMs = Math.round(((System.nanoTime() - started) / 1_000_000.0) * 100.0) / 100.0;
            respond(exchange, 200, "{"
                    + "\"cacheBackend\":\"" + Json.escape(backend) + "\""
                    + ",\"start\":" + startId
                    + ",\"total\":" + total
                    + ",\"percent\":" + percent
                    + ",\"requested\":" + limit
                    + ",\"warmed\":" + products.size()
                    + ",\"cleared\":" + clear
                    + ",\"statsReset\":" + resetStats
                    + ",\"elapsedMs\":" + elapsedMs
                    + ",\"cacheSize\":" + provider.size()
                    + "}");
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            respond(exchange, 500, error(e.getMessage()));
        }
    }

    private void metrics(HttpExchange exchange) throws IOException {
        respond(exchange, 200, metrics.toJson(cacheProvider.sizes(), clusterSize(), nodeName, cacheProvider.name()));
    }

    private void resetMetrics(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, error("Method not allowed."));
            return;
        }
        try {
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/metrics/reset/") || path.length() == "/metrics/reset/".length()) {
                throw new IllegalArgumentException("Missing cache backend.");
            }
            String backend = path.substring("/metrics/reset/".length()).toLowerCase();
            cacheProvider.provider(backend);
            metrics.reset(backend);
            respond(exchange, 200, "{\"reset\":true"
                    + ",\"cacheBackend\":\"" + Json.escape(backend) + "\"}");
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 500, error(e.getMessage()));
        }
    }

    private void cacheBackend(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/cache/backend".equals(path)) {
                /*
                 * Retorna o backend ativo e a saude dos providers registrados.
                 */
                respond(exchange, 200, cacheProvider.statusJson());
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/cache/backend/")) {
                /*
                 * Troca dinamicamente o backend. Nao reinicia a JVM, nao muda o
                 * codigo de negocio e nao altera o Oracle Database.
                 */
                String backend = path.substring("/cache/backend/".length()).toLowerCase();
                if ("toggle".equals(backend)) {
                    cacheProvider.switchToNext();
                } else {
                    cacheProvider.switchTo(backend);
                }
                respond(exchange, 200, cacheProvider.statusJson());
                return;
            }
            respond(exchange, 405, error("Method not allowed."));
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        }
    }

    private void managementProxy(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, error("Method not allowed."));
            return;
        }

        /*
         * A tela chama /management-proxy/... no app-bastion. O app encaminha a
         * requisicao para o endpoint privado do Coherence Management na VM de
         * storage. Assim a porta 30000 continua privada.
         */
        String path = exchange.getRequestURI().getRawPath();
        String suffix = path.length() > "/management-proxy".length()
                ? path.substring("/management-proxy".length())
                : "/";
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }

        String query = exchange.getRequestURI().getRawQuery();
        URI target = URI.create(managementBaseUrl + suffix
                + (query == null || query.isBlank() ? "" : "?" + query));

        try {
            HttpRequest request = HttpRequest.newBuilder(target).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            respond(exchange, response.statusCode(), "application/json", response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            respond(exchange, 500, error("Management request interrupted."));
        } catch (Exception e) {
            respond(exchange, 502, error("Management endpoint unavailable: " + e.getMessage()));
        }
    }

    private int clusterSize() {
        /*
         * Como a app tambem e membro do cluster, ela consegue consultar o
         * conjunto de membros diretamente pela API do Coherence.
         */
        return CacheFactory.ensureCluster().getMemberSet().size();
    }

    private long cacheSize() {
        try {
            return cacheProvider.activeProvider().size();
        } catch (Exception e) {
            return -1;
        }
    }

    private long idFromPath(HttpExchange exchange, String prefix) {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(prefix) || path.length() == prefix.length()) {
            throw new IllegalArgumentException("Missing id.");
        }
        return Long.parseLong(path.substring(prefix.length()));
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String queryParam(HttpExchange exchange, String name, String defaultValue) {
        return optionalQueryParam(exchange, name).orElse(defaultValue);
    }

    private Optional<String> optionalQueryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            if (name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                String value = separator < 0 ? "" : pair.substring(separator + 1);
                return Optional.of(URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    private void respond(HttpExchange exchange, int status, String json) throws IOException {
        respond(exchange, status, "application/json", json);
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static String error(String message) {
        return "{\"error\":\"" + Json.escape(message) + "\"}";
    }

    private static String env(String name, String defaultValue) {
        /*
         * Pequeno helper para manter a POC configuravel por systemd/cloud-init
         * sem precisar de framework externo de configuracao.
         */
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static final String CONSOLE_HTML = """
            <!doctype html>
            <html lang="pt-BR">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>POC Coherence Console</title>
              <style>
                :root {
                  color-scheme: light;
                  --bg: #f4f6f8;
                  --panel: #ffffff;
                  --soft: #eef2f6;
                  --line: #d9e0e8;
                  --text: #1b2430;
                  --muted: #617083;
                  --accent: #c74634;
                  --redis: #b42318;
                  --coherence: #1f6f63;
                  --blue: #255f99;
                  --warn: #9a5b00;
                  --good: #177245;
                  --bad: #b42318;
                  --shadow: 0 8px 22px rgba(27, 36, 48, .08);
                }

                * { box-sizing: border-box; }

                body {
                  margin: 0;
                  background: var(--bg);
                  color: var(--text);
                  font: 14px/1.45 system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  min-width: 1220px;
                }

                h1, h2, h3, p { margin: 0; }

                header {
                  position: sticky;
                  top: 0;
                  z-index: 5;
                  display: grid;
                  grid-template-columns: minmax(460px, 1fr) auto;
                  gap: 16px;
                  align-items: center;
                  padding: 14px 28px;
                  border-bottom: 1px solid var(--line);
                  background: rgba(255, 255, 255, .96);
                  backdrop-filter: blur(10px);
                }

                h1 {
                  font-size: 20px;
                  font-weight: 750;
                }

                .subtitle {
                  margin-top: 2px;
                  color: var(--muted);
                  font-size: 13px;
                }

                .top-actions {
                  display: flex;
                  flex-wrap: wrap;
                  justify-content: flex-end;
                  gap: 8px;
                  align-items: center;
                }

                button, input {
                  height: 34px;
                  border: 1px solid var(--line);
                  border-radius: 6px;
                  background: #ffffff;
                  color: var(--text);
                  font: inherit;
                }

                button {
                  padding: 0 12px;
                  cursor: pointer;
                  font-weight: 700;
                }

                button.primary {
                  background: var(--accent);
                  border-color: var(--accent);
                  color: #ffffff;
                }

                button.secondary {
                  background: #1b2430;
                  border-color: #1b2430;
                  color: #ffffff;
                }

                button.ghost {
                  background: #ffffff;
                }

                button.danger {
                  border-color: #e6b8ae;
                  color: var(--bad);
                }

                button.small {
                  height: 30px;
                  padding: 0 9px;
                  font-size: 12px;
                }

                button.warm {
                  background: #fff4df;
                  border-color: #f0c36a;
                  color: var(--warn);
                }

                button.mode.active {
                  background: var(--blue);
                  border-color: var(--blue);
                  color: #ffffff;
                }

                button:disabled {
                  cursor: not-allowed;
                  opacity: .58;
                }

                input {
                  width: 88px;
                  padding: 0 10px;
                }

                main {
                  max-width: 1680px;
                  margin: 0 auto;
                  padding: 18px 28px 34px;
                }

                .status-line {
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                  gap: 12px;
                  margin-bottom: 14px;
                  color: var(--muted);
                  font-size: 13px;
                }

                .dot {
                  display: inline-block;
                  width: 9px;
                  height: 9px;
                  border-radius: 999px;
                  margin-right: 7px;
                  background: var(--warn);
                }

                .dot.ok { background: var(--good); }
                .dot.bad { background: var(--bad); }
                .dot.live { background: var(--blue); }

                .pill {
                  display: inline-flex;
                  align-items: center;
                  gap: 6px;
                  min-height: 26px;
                  padding: 3px 9px;
                  border-radius: 999px;
                  background: var(--soft);
                  color: var(--muted);
                  font-size: 12px;
                  font-weight: 750;
                  white-space: nowrap;
                }

                .pill.redis {
                  background: #fff0ee;
                  color: var(--redis);
                }

                .pill.coherence {
                  background: #eaf7f3;
                  color: var(--coherence);
                }

                .layout {
                  display: grid;
                  grid-template-columns: 360px minmax(380px, 1fr) minmax(380px, 1fr);
                  gap: 16px;
                  align-items: start;
                }

                .panel {
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 8px;
                  box-shadow: var(--shadow);
                  overflow: hidden;
                }

                .panel-head {
                  min-height: 68px;
                  display: flex;
                  justify-content: space-between;
                  gap: 10px;
                  align-items: center;
                  padding: 14px 16px;
                  border-bottom: 1px solid var(--line);
                  background: #ffffff;
                }

                .panel-head h2 {
                  font-size: 16px;
                  font-weight: 780;
                }

                .panel-head p {
                  margin-top: 2px;
                  color: var(--muted);
                  font-size: 12px;
                }

                .panel-tools {
                  display: flex;
                  align-items: center;
                  justify-content: flex-end;
                  flex-wrap: wrap;
                  gap: 6px;
                  max-width: 340px;
                }

                .warm-control {
                  display: inline-flex;
                  align-items: center;
                  gap: 5px;
                  height: 30px;
                  padding: 0 7px;
                  border: 1px solid var(--line);
                  border-radius: 6px;
                  background: #ffffff;
                  color: var(--muted);
                  font-size: 11px;
                  font-weight: 750;
                  text-transform: uppercase;
                  white-space: nowrap;
                }

                .warm-control input {
                  width: 54px;
                  height: 24px;
                  padding: 0 5px;
                  font-size: 12px;
                  font-weight: 700;
                }

                .panel-body {
                  padding: 16px;
                }

                .control-row {
                  display: flex;
                  align-items: center;
                  flex-wrap: wrap;
                  gap: 8px;
                  margin-bottom: 12px;
                }

                .field {
                  display: inline-flex;
                  flex-direction: column;
                  gap: 4px;
                }

                .field label {
                  color: var(--muted);
                  font-size: 11px;
                  font-weight: 750;
                  text-transform: uppercase;
                }

                .stats {
                  display: grid;
                  grid-template-columns: repeat(2, minmax(0, 1fr));
                  gap: 8px;
                }

                .stat {
                  min-height: 68px;
                  padding: 10px;
                  border: 1px solid var(--line);
                  border-radius: 8px;
                  background: #fbfcfd;
                }

                .stat label {
                  display: block;
                  color: var(--muted);
                  font-size: 11px;
                  font-weight: 750;
                  text-transform: uppercase;
                }

                .stat strong {
                  display: block;
                  margin-top: 4px;
                  font-size: 21px;
                  font-weight: 800;
                  overflow-wrap: anywhere;
                }

                .stat span {
                  display: block;
                  margin-top: 1px;
                  color: var(--muted);
                  font-size: 12px;
                  overflow-wrap: anywhere;
                }

                .stat.wide {
                  grid-column: 1 / -1;
                }

                .payload-title {
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                  gap: 8px;
                  margin: 14px 0 8px;
                  color: var(--muted);
                  font-size: 12px;
                  font-weight: 750;
                  text-transform: uppercase;
                }

                .warm-banner {
                  display: none;
                  margin-bottom: 12px;
                  padding: 10px 12px;
                  border: 1px solid #f0c36a;
                  border-radius: 8px;
                  background: #fff8e8;
                  color: var(--warn);
                  font-size: 13px;
                  font-weight: 700;
                }

                .warm-banner.active {
                  display: block;
                }

                pre {
                  margin: 0;
                  min-height: 220px;
                  max-height: 420px;
                  overflow: auto;
                  padding: 12px;
                  border-radius: 8px;
                  background: #121820;
                  color: #e8edf5;
                  font-size: 12px;
                  line-height: 1.5;
                  white-space: pre-wrap;
                  word-break: break-word;
                }

                .log {
                  display: grid;
                  gap: 8px;
                  max-height: 430px;
                  overflow: auto;
                  padding-right: 2px;
                }

                .log-item {
                  border: 1px solid var(--line);
                  border-left: 4px solid var(--blue);
                  border-radius: 8px;
                  padding: 9px 10px;
                  background: #fbfcfd;
                }

                .log-item.redis { border-left-color: var(--redis); }
                .log-item.coherence { border-left-color: var(--coherence); }

                .log-item strong {
                  display: block;
                  font-size: 13px;
                }

                .log-item span {
                  color: var(--muted);
                  font-size: 12px;
                }

                .backend.redis .panel-head {
                  border-top: 3px solid var(--redis);
                }

                .backend.coherence .panel-head {
                  border-top: 3px solid var(--coherence);
                }
              </style>
            </head>
            <body>
              <header>
                <div>
                  <h1>POC Redis x Oracle Coherence</h1>
                  <p class="subtitle">A app consulta sempre o mesmo endpoint; a troca de cache acontece atras do CacheProvider.</p>
                </div>
                <div class="top-actions">
                  <span class="pill" id="activeBackendPill">Ativo: -</span>
                  <button class="primary" id="toggleBackendBtn">Intercambiar cache</button>
                  <button class="ghost" id="refreshBtn">Atualizar</button>
                  <button class="ghost" id="openMgmtBtn">Coherence REST</button>
                </div>
              </header>

              <main>
                <div class="status-line">
                  <span><span class="dot" id="statusDot"></span><span id="statusText">Carregando</span></span>
                  <span id="lastRefresh">-</span>
                </div>

                <section class="layout" aria-label="Console da POC">
                  <article class="panel">
                    <div class="panel-head">
                      <div>
                        <h2>Robo de consultas</h2>
                        <p>GET /products/{id} no intervalo configurado</p>
                      </div>
                      <span class="pill" id="robotState">parado</span>
                    </div>
                    <div class="panel-body">
                      <div class="control-row">
                        <div class="field">
                          <label for="robotStartId">ID inicial</label>
                          <input id="robotStartId" type="number" min="1" value="1">
                        </div>
                        <div class="field">
                          <label for="robotMaxId">ID maximo</label>
                          <input id="robotMaxId" type="number" min="1" value="50000">
                        </div>
                        <div class="field">
                          <label for="robotIntervalMs">Intervalo ms</label>
                          <input id="robotIntervalMs" type="number" min="100" step="100" value="500">
                        </div>
                        <button class="mode active" id="sequentialModeBtn">Sequencial</button>
                        <button class="mode" id="randomModeBtn">Aleatorio</button>
                        <button class="secondary" id="robotToggleBtn">Iniciar robo</button>
                      </div>

                      <div class="stats">
                        <div class="stat">
                          <label>Consultas</label>
                          <strong id="robotRequests">0</strong>
                          <span id="robotRate">sequencial | 500 ms</span>
                        </div>
                        <div class="stat">
                          <label>Ultimo ID</label>
                          <strong id="robotLastId">-</strong>
                          <span id="robotLastBackend">backend -</span>
                        </div>
                        <div class="stat">
                          <label>Ultimo tempo</label>
                          <strong id="robotLastMs">-</strong>
                          <span>tempo percebido pelo browser</span>
                        </div>
                        <div class="stat">
                          <label>Resultado</label>
                          <strong id="robotLastHit">-</strong>
                          <span id="robotLastName">-</span>
                        </div>
                      </div>

                      <div class="payload-title">
                        <span>Execucao do robo</span>
                        <span id="robotEndpoint">/products/-</span>
                      </div>
                      <div class="log" id="robotLog"></div>
                    </div>
                  </article>

                  <article class="panel backend redis">
                    <div class="panel-head">
                      <div>
                        <h2>Redis</h2>
                        <p>Implementacao RedisCacheProvider</p>
                      </div>
                      <div class="panel-tools">
                        <span class="pill redis" id="redisActive">standby</span>
                        <label class="warm-control" for="redisWarmPercent">Warm %
                          <input id="redisWarmPercent" type="number" min="0" max="100" step="5" value="50">
                        </label>
                        <button class="small warm" id="redisWarmBtn">Warm-up</button>
                        <button class="small" id="redisClearCacheBtn">Zerar cache</button>
                        <button class="small" id="redisResetStatsBtn">Zerar stats</button>
                      </div>
                    </div>
                    <div class="panel-body">
                      <div class="warm-banner" id="redisWarmBanner">Preparando Redis...</div>
                      <div class="stats" id="redisStats"></div>
                      <div class="payload-title">
                        <span>Ultimo payload via Redis</span>
                        <span id="redisPayloadMeta">sem leitura</span>
                      </div>
                      <pre id="redisPayload">{}</pre>
                    </div>
                  </article>

                  <article class="panel backend coherence">
                    <div class="panel-head">
                      <div>
                        <h2>Oracle Coherence</h2>
                        <p>Implementacao CoherenceCacheProvider</p>
                      </div>
                      <div class="panel-tools">
                        <span class="pill coherence" id="coherenceActive">standby</span>
                        <label class="warm-control" for="coherenceWarmPercent">Warm %
                          <input id="coherenceWarmPercent" type="number" min="0" max="100" step="5" value="50">
                        </label>
                        <button class="small warm" id="coherenceWarmBtn">Warm-up</button>
                        <button class="small" id="coherenceClearCacheBtn">Zerar cache</button>
                        <button class="small" id="coherenceResetStatsBtn">Zerar stats</button>
                      </div>
                    </div>
                    <div class="panel-body">
                      <div class="warm-banner" id="coherenceWarmBanner">Preparando Coherence...</div>
                      <div class="stats" id="coherenceStats"></div>
                      <div class="payload-title">
                        <span>Ultimo payload via Coherence</span>
                        <span id="coherencePayloadMeta">sem leitura</span>
                      </div>
                      <pre id="coherencePayload">{}</pre>
                    </div>
                  </article>
                </section>
              </main>

              <script>
                const $ = (id) => document.getElementById(id);

                const state = {
                  activeBackend: '-',
                  robotTimer: null,
                  robotBusy: false,
                  robotNextId: 1,
                  robotMode: 'sequential',
                  robotRequests: 0,
                  payloads: {
                    redis: null,
                    coherence: null
                  }
                };

                async function getJson(url, options = {}) {
                  const response = await fetch(url, {
                    headers: { 'Accept': 'application/json', ...(options.headers || {}) },
                    ...options
                  });
                  const text = await response.text();
                  let body;
                  try {
                    body = text ? JSON.parse(text) : {};
                  } catch {
                    body = { raw: text };
                  }
                  if (!response.ok) {
                    throw new Error(body.error || `${response.status} ${response.statusText}`);
                  }
                  return body;
                }

                async function timedJson(url, options = {}) {
                  const started = performance.now();
                  const body = await getJson(url, options);
                  return {
                    body,
                    elapsedMs: Number((performance.now() - started).toFixed(2))
                  };
                }

                function fmt(value) {
                  if (value === undefined || value === null || value === '') return '-';
                  return String(value);
                }

                function fmtMs(value) {
                  const numeric = Number(value || 0);
                  return `${numeric.toFixed(2)} ms`;
                }

                function fmtPct(value) {
                  const numeric = Number(value || 0);
                  return `${numeric.toFixed(1)}%`;
                }

                function escapeHtml(value) {
                  return String(value)
                    .replaceAll('&', '&amp;')
                    .replaceAll('<', '&lt;')
                    .replaceAll('>', '&gt;')
                    .replaceAll('"', '&quot;')
                    .replaceAll("'", '&#039;');
                }

                function setStatus(mode, text) {
                  $('statusDot').className = `dot ${mode}`;
                  $('statusText').textContent = text;
                }

                function defaultBackendStats() {
                  return {
                    cacheSize: 0,
                    requests: 0,
                    hits: 0,
                    misses: 0,
                    totalReads: 0,
                    cacheReads: 0,
                    cacheWrites: 0,
                    dbReads: 0,
                    dbWrites: 0,
                    invalidations: 0,
                    errors: 0,
                    hitRatePct: 0,
                    missRatePct: 0,
                    dbReadRatePct: 0,
                    errorRatePct: 0,
                    lastCacheMs: 0,
                    avgCacheMs: 0,
                    avgReadCacheMs: 0,
                    lastHitCacheMs: 0,
                    avgHitCacheMs: 0,
                    lastMissCacheMs: 0,
                    avgMissCacheMs: 0,
                    lastResponseMs: 0,
                    avgResponseMs: 0,
                    avgReadResponseMs: 0,
                    lastHitResponseMs: 0,
                    avgHitResponseMs: 0,
                    lastMissResponseMs: 0,
                    avgMissResponseMs: 0,
                    lastDbMs: 0,
                    avgDbMs: 0,
                    estimatedDbAvoided: 0,
                    responseGainMs: 0,
                    speedGainPct: 0,
                    efficiencyScorePct: 0
                  };
                }

                function statCard(label, value, detail = '') {
                  return `
                    <div class="stat">
                      <label>${escapeHtml(label)}</label>
                      <strong>${escapeHtml(value)}</strong>
                      <span>${escapeHtml(detail)}</span>
                    </div>
                  `;
                }

                function renderBackend(name, metrics) {
                  const stats = metrics.backends?.[name] || defaultBackendStats();
                  const active = state.activeBackend === name;
                  const activeEl = $(`${name}Active`);
                  activeEl.textContent = active ? 'ativo' : 'standby';
                  activeEl.classList.toggle(name, true);
                  const mixedResponseMs = stats.avgReadResponseMs ?? stats.avgResponseMs;
                  const mixedCacheMs = stats.avgReadCacheMs ?? stats.avgCacheMs;

                  $(`${name}Stats`).innerHTML = [
                    statCard('Consultas', fmt(stats.requests), `${fmt(stats.totalReads)} leituras de cache`),
                    statCard('Hit / miss', `${fmtPct(stats.hitRatePct)} / ${fmtPct(stats.missRatePct)}`, `${fmt(stats.hits)} hits | ${fmt(stats.misses)} misses`),
                    statCard('Media hit', fmtMs(stats.avgHitResponseMs), `cache ${fmtMs(stats.avgHitCacheMs)}`),
                    statCard('Media miss', fmtMs(stats.avgMissResponseMs), `cache ${fmtMs(stats.avgMissCacheMs)}`),
                    statCard('Media composta', fmtMs(mixedResponseMs), `cache ${fmtMs(mixedCacheMs)}`),
                    statCard('Score cache', fmtPct(stats.efficiencyScorePct), `ganho ${fmtPct(stats.speedGainPct)} | erros ${fmtPct(stats.errorRatePct)}`),
                    statCard('DB evitado', fmt(stats.estimatedDbAvoided), `${fmtPct(stats.dbReadRatePct)} foi ao Oracle`),
                    statCard('Cache entries', fmt(stats.cacheSize), `${fmt(stats.cacheWrites)} writes | ${fmt(stats.invalidations)} resets`)
                  ].join('');

                  const payload = state.payloads[name];
                  if (payload) {
                    $(`${name}PayloadMeta`).textContent = `id ${payload.id} | ${payload.clientMs} ms | ${payload.at}`;
                    $(`${name}Payload`).textContent = JSON.stringify(payload.body, null, 2);
                  } else {
                    $(`${name}PayloadMeta`).textContent = 'sem leitura';
                    $(`${name}Payload`).textContent = '{}';
                  }
                }

                async function refresh() {
                  try {
                    const [health, metrics, backend] = await Promise.all([
                      getJson('/health'),
                      getJson('/metrics'),
                      getJson('/cache/backend')
                    ]);
                    state.activeBackend = backend.active;
                    const activeClass = backend.active === 'coherence' ? 'coherence' : 'redis';
                    $('activeBackendPill').className = `pill ${activeClass}`;
                    $('activeBackendPill').textContent = `Ativo: ${backend.active}`;
                    $('toggleBackendBtn').textContent = backend.active === 'redis'
                      ? 'Intercambiar para Coherence'
                      : 'Intercambiar para Redis';
                    renderBackend('redis', metrics);
                    renderBackend('coherence', metrics);
                    $('lastRefresh').textContent = `Atualizado ${new Date().toLocaleTimeString()}`;
                    setStatus(health.db && health.cacheUp ? 'ok' : 'bad',
                      `App ${health.node} | cluster ${health.clusterSize} | DB ${health.db ? 'OK' : 'falha'}`);
                  } catch (error) {
                    setStatus('bad', error.message);
                  }
                }

                function robotBounds() {
                  const start = Math.max(1, Number($('robotStartId').value || 1));
                  const max = Math.max(start, Number($('robotMaxId').value || start));
                  return { start, max };
                }

                function robotIntervalMs() {
                  return Math.max(100, Number($('robotIntervalMs').value || 500));
                }

                function updateRobotRate() {
                  $('robotRate').textContent = `${state.robotMode === 'random' ? 'aleatorio' : 'sequencial'} | ${robotIntervalMs()} ms`;
                }

                function setRobotMode(mode) {
                  state.robotMode = mode;
                  $('sequentialModeBtn').classList.toggle('active', mode === 'sequential');
                  $('randomModeBtn').classList.toggle('active', mode === 'random');
                  updateRobotRate();
                }

                function nextRobotId() {
                  const bounds = robotBounds();
                  if (state.robotMode === 'random') {
                    return bounds.start + Math.floor(Math.random() * (bounds.max - bounds.start + 1));
                  }
                  if (state.robotNextId < bounds.start || state.robotNextId > bounds.max) {
                    state.robotNextId = bounds.start;
                  }
                  const id = state.robotNextId;
                  state.robotNextId = id >= bounds.max ? bounds.start : id + 1;
                  return id;
                }

                function appendRobotLog(entry) {
                  const item = document.createElement('div');
                  item.className = `log-item ${entry.backend}`;
                  item.innerHTML = `
                    <strong>#${entry.count} GET /products/${entry.id} -> ${escapeHtml(entry.backend)}</strong>
                    <span>${escapeHtml(entry.hit)} | ${entry.clientMs} ms | ${escapeHtml(entry.name)} | ${entry.at}</span>
                  `;
                  $('robotLog').prepend(item);
                  while ($('robotLog').children.length > 14) {
                    $('robotLog').lastElementChild.remove();
                  }
                }

                async function robotTick() {
                  if (state.robotBusy) {
                    return;
                  }
                  state.robotBusy = true;
                  const id = nextRobotId();
                  $('robotEndpoint').textContent = `/products/${id}`;
                  try {
                    const result = await timedJson(`/products/${id}`);
                    const body = result.body;
                    const backend = body.cacheBackend || state.activeBackend;
                    const at = new Date().toLocaleTimeString();
                    const hit = body.cacheHit ? 'cache hit' : 'cache miss';
                    state.robotRequests += 1;
                    state.payloads[backend] = {
                      id,
                      at,
                      clientMs: result.elapsedMs,
                      body: {
                        ...body,
                        browserResponseMs: result.elapsedMs
                      }
                    };

                    $('robotRequests').textContent = state.robotRequests;
                    $('robotLastId').textContent = id;
                    $('robotLastBackend').textContent = `backend ${backend}`;
                    $('robotLastMs').textContent = fmtMs(result.elapsedMs);
                    $('robotLastHit').textContent = hit;
                    $('robotLastName').textContent = body.name || '-';
                    appendRobotLog({
                      count: state.robotRequests,
                      id,
                      backend,
                      hit,
                      clientMs: result.elapsedMs,
                      name: body.name || '-',
                      at
                    });
                    await refresh();
                  } catch (error) {
                    setStatus('bad', error.message);
                  } finally {
                    state.robotBusy = false;
                  }
                }

                function setRobotRunning(running) {
                  if (running && !state.robotTimer) {
                    state.robotNextId = Number($('robotStartId').value || 1);
                    state.robotTimer = setInterval(robotTick, robotIntervalMs());
                    robotTick();
                  } else if (!running && state.robotTimer) {
                    clearInterval(state.robotTimer);
                    state.robotTimer = null;
                  }
                  updateRobotRate();
                  $('robotToggleBtn').textContent = state.robotTimer ? 'Pausar robo' : 'Iniciar robo';
                  $('robotState').textContent = state.robotTimer ? 'rodando' : 'parado';
                  $('robotState').className = `pill ${state.robotTimer ? 'coherence' : ''}`;
                  setStatus(state.robotTimer ? 'live' : 'ok', state.robotTimer ? 'Robo em execucao' : 'Console conectada');
                }

                function restartRobotTimerIfRunning() {
                  updateRobotRate();
                  if (!state.robotTimer) {
                    return;
                  }
                  clearInterval(state.robotTimer);
                  state.robotTimer = setInterval(robotTick, robotIntervalMs());
                }

                async function toggleBackend() {
                  const result = await getJson('/cache/backend/toggle', { method: 'POST' });
                  state.activeBackend = result.active;
                  await refresh();
                }

                async function clearBackendCache(backend) {
                  await getJson(`/cache/clear/${backend}`, { method: 'POST' });
                  state.payloads[backend] = null;
                  await refresh();
                }

                async function resetBackendStats(backend) {
                  await getJson(`/metrics/reset/${backend}`, { method: 'POST' });
                  state.payloads[backend] = null;
                  await refresh();
                }

                function setBackendBusy(backend, busy, message = '') {
                  const banner = $(`${backend}WarmBanner`);
                  const buttons = [
                    $(`${backend}WarmBtn`),
                    $(`${backend}ClearCacheBtn`),
                    $(`${backend}ResetStatsBtn`),
                    $(`${backend}WarmPercent`)
                  ];
                  banner.textContent = message;
                  banner.classList.toggle('active', busy || Boolean(message));
                  buttons.forEach((button) => {
                    button.disabled = busy;
                  });
                }

                function warmPercent(backend) {
                  const input = $(`${backend}WarmPercent`);
                  const raw = Number(input.value);
                  const value = Number.isFinite(raw) ? Math.max(0, Math.min(100, raw)) : 50;
                  input.value = String(value);
                  return value;
                }

                async function warmBackend(backend) {
                  const label = backend === 'redis' ? 'Redis' : 'Coherence';
                  const percent = warmPercent(backend);
                  const total = 50000;
                  const requested = Math.round(total * (percent / 100));
                  const started = performance.now();
                  setBackendBusy(backend, true, `Warm-up do ${label} em andamento: ${fmtPct(percent)} (${requested} registros)`);
                  setStatus('live', `Warm-up do ${label} em andamento`);
                  try {
                    const result = await getJson(`/cache/warm/${backend}?start=1&percent=${encodeURIComponent(percent)}&total=${total}&clear=true&resetStats=true`, {
                      method: 'POST'
                    });
                    const elapsed = Number((performance.now() - started).toFixed(2));
                    state.payloads[backend] = {
                      id: 'warm-up',
                      at: new Date().toLocaleTimeString(),
                      clientMs: elapsed,
                      body: {
                        action: 'warm-up',
                        ...result,
                        browserResponseMs: elapsed
                      }
                    };
                    setBackendBusy(backend, false, `${label}: ${result.warmed} registros (${fmtPct(result.percent)}) em cache; stats zeradas`);
                    await refresh();
                  } catch (error) {
                    setBackendBusy(backend, false, `${label}: erro no warm-up`);
                    setStatus('bad', error.message);
                  }
                }

                $('robotToggleBtn').addEventListener('click', () => setRobotRunning(!state.robotTimer));
                $('sequentialModeBtn').addEventListener('click', () => setRobotMode('sequential'));
                $('randomModeBtn').addEventListener('click', () => setRobotMode('random'));
                $('robotIntervalMs').addEventListener('input', updateRobotRate);
                $('robotIntervalMs').addEventListener('change', restartRobotTimerIfRunning);
                $('redisWarmBtn').addEventListener('click', () => warmBackend('redis'));
                $('redisClearCacheBtn').addEventListener('click', () => clearBackendCache('redis').catch((e) => setStatus('bad', e.message)));
                $('redisResetStatsBtn').addEventListener('click', () => resetBackendStats('redis').catch((e) => setStatus('bad', e.message)));
                $('coherenceWarmBtn').addEventListener('click', () => warmBackend('coherence'));
                $('coherenceClearCacheBtn').addEventListener('click', () => clearBackendCache('coherence').catch((e) => setStatus('bad', e.message)));
                $('coherenceResetStatsBtn').addEventListener('click', () => resetBackendStats('coherence').catch((e) => setStatus('bad', e.message)));
                $('toggleBackendBtn').addEventListener('click', () => toggleBackend().catch((e) => setStatus('bad', e.message)));
                $('refreshBtn').addEventListener('click', () => refresh().catch((e) => setStatus('bad', e.message)));
                $('openMgmtBtn').addEventListener('click', () => window.open('/management-proxy/cluster', '_blank'));

                setRobotMode('sequential');
                refresh();
                setInterval(() => {
                  if (!state.robotTimer) {
                    refresh();
                  }
                }, 5000);
              </script>
            </body>
            </html>
            """;
}
