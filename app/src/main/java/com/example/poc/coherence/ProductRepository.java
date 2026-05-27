package com.example.poc.coherence;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Acesso ao Oracle Database.
 *
 * Nesta POC, o banco e a fonte oficial dos produtos. Redis e Coherence sao
 * apenas camadas de cache: se um item nao estiver no cache ativo, a aplicacao
 * busca aqui e depois repopula o cache.
 */
final class ProductRepository {
    private final String url;
    private final String user;
    private final String password;

    ProductRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    Optional<Product> findById(long id) throws SQLException {
        String sql = "select id, name, price, updated_at from products where id = ?";
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            /*
             * PreparedStatement evita concatenar parametros no SQL e deixa claro
             * que o id e um valor, nao parte do comando.
             */
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(map(resultSet));
            }
        }
    }

    List<Product> findRange(long startId, int limit) throws SQLException {
        /*
         * Usado pelo warm-up da demo. Uma unica consulta traz o intervalo do
         * Oracle e evita milhares de conexoes concorrentes durante a preparacao.
         */
        String sql = """
                select id, name, price, updated_at
                from products
                where id between ? and ?
                order by id
                """;
        long endId = startId + limit - 1L;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, startId);
            statement.setLong(2, endId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Product> products = new ArrayList<>();
                while (resultSet.next()) {
                    products.add(map(resultSet));
                }
                return products;
            }
        }
    }

    Product upsert(long id, String name, BigDecimal price) throws SQLException {
        /*
         * MERGE permite atualizar se o produto ja existe ou inserir se for novo.
         * Isso simplifica o endpoint PUT da demo.
         */
        String merge = """
                merge into products target
                using (select ? id, ? name, ? price from dual) source
                on (target.id = source.id)
                when matched then update set
                  target.name = source.name,
                  target.price = source.price,
                  target.updated_at = systimestamp
                when not matched then insert (id, name, price, updated_at)
                  values (source.id, source.name, source.price, systimestamp)
                """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(merge)) {
            statement.setLong(1, id);
            statement.setString(2, name);
            statement.setBigDecimal(3, price);
            statement.executeUpdate();
        }
        return findById(id).orElseThrow();
    }

    boolean ping() {
        /*
         * Query minima para validar se a conexao com o Oracle esta operacional.
         */
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("select 1 from dual");
             ResultSet ignored = statement.executeQuery()) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private Connection connect() throws SQLException {
        /*
         * A POC abre conexoes diretamente para manter o codigo pequeno. Em uma
         * aplicacao real, normalmente haveria um pool de conexoes.
         */
        return DriverManager.getConnection(url, user, password);
    }

    private Product map(ResultSet resultSet) throws SQLException {
        /*
         * Converte a linha SQL para o record usado pela API e pelos caches.
         */
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        Instant instant = updatedAt == null ? Instant.EPOCH : updatedAt.toInstant();
        return new Product(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getBigDecimal("price"),
                instant
        );
    }
}
