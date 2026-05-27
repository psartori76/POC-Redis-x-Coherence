package com.example.poc.coherence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilitarios JSON pequenos para evitar trazer um framework adicional para a POC.
 *
 * Como o payload e controlado e simples, regex e suficiente aqui. Em producao,
 * uma biblioteca como Jackson seria mais adequada para validacao completa,
 * escaping amplo e evolucao de contrato.
 */
final class Json {
    /*
     * Padroes usados tanto para ler o corpo do PUT quanto para reconstituir um
     * Product salvo como JSON no Redis.
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PRICE_PATTERN = Pattern.compile("\"price\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*([0-9]+)");
    private static final Pattern UPDATED_AT_PATTERN = Pattern.compile("\"updatedAt\"\\s*:\\s*\"([^\"]+)\"");

    private Json() {
    }

    static String escape(String value) {
        /*
         * Escaping minimo para os JSONs gerados manualmente nesta POC.
         */
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static ProductInput parseProductInput(String body) {
        /*
         * Converte o JSON de entrada do PUT /products/{id} para um objeto pequeno
         * com os campos editaveis pelo usuario.
         */
        Matcher nameMatcher = NAME_PATTERN.matcher(body);
        Matcher priceMatcher = PRICE_PATTERN.matcher(body);
        if (!nameMatcher.find() || !priceMatcher.find()) {
            throw new IllegalArgumentException("Expected JSON body with name and price.");
        }
        return new ProductInput(nameMatcher.group(1), new BigDecimal(priceMatcher.group(1)));
    }

    static String toCacheJson(Product product) {
        /*
         * Formato usado no Redis. Coherence guarda o Product diretamente no
         * NamedMap; Redis guarda texto, por isso precisa desta serializacao.
         */
        return "{"
                + "\"id\":" + product.id()
                + ",\"name\":\"" + escape(product.name()) + "\""
                + ",\"price\":" + product.price()
                + ",\"updatedAt\":\"" + product.updatedAt() + "\""
                + "}";
    }

    static Product parseCachedProduct(String body) {
        /*
         * Faz o caminho inverso do Redis: transforma o JSON salvo na chave em
         * Product para o restante da aplicacao continuar usando o mesmo modelo.
         */
        Matcher idMatcher = ID_PATTERN.matcher(body);
        Matcher nameMatcher = NAME_PATTERN.matcher(body);
        Matcher priceMatcher = PRICE_PATTERN.matcher(body);
        Matcher updatedAtMatcher = UPDATED_AT_PATTERN.matcher(body);
        if (!idMatcher.find() || !nameMatcher.find() || !priceMatcher.find() || !updatedAtMatcher.find()) {
            throw new IllegalArgumentException("Expected cached product JSON.");
        }
        return new Product(
                Long.parseLong(idMatcher.group(1)),
                nameMatcher.group(1),
                new BigDecimal(priceMatcher.group(1)),
                Instant.parse(updatedAtMatcher.group(1))
        );
    }

    record ProductInput(String name, BigDecimal price) {
    }
}
