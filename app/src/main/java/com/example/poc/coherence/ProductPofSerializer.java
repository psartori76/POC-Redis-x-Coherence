package com.example.poc.coherence;

import com.tangosol.io.pof.PofReader;
import com.tangosol.io.pof.PofSerializer;
import com.tangosol.io.pof.PofWriter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Serializador POF do produto usado no Coherence.
 *
 * POF grava campos por indice e evita o custo da serializacao Java padrao. Isso
 * reduz CPU e bytes trafegados entre a app e o storage node em leituras/escritas
 * de cache.
 */
public final class ProductPofSerializer implements PofSerializer<Product> {
    private static final int ID = 0;
    private static final int NAME = 1;
    private static final int PRICE = 2;
    private static final int UPDATED_AT_EPOCH_SECOND = 3;
    private static final int UPDATED_AT_NANO = 4;

    @Override
    public void serialize(PofWriter writer, Product product) throws IOException {
        writer.writeLong(ID, product.id());
        writer.writeString(NAME, product.name());
        writer.writeBigDecimal(PRICE, product.price());
        writer.writeLong(UPDATED_AT_EPOCH_SECOND, product.updatedAt().getEpochSecond());
        writer.writeInt(UPDATED_AT_NANO, product.updatedAt().getNano());
        writer.writeRemainder(null);
    }

    @Override
    public Product deserialize(PofReader reader) throws IOException {
        long id = reader.readLong(ID);
        String name = reader.readString(NAME);
        BigDecimal price = reader.readBigDecimal(PRICE);
        long epochSecond = reader.readLong(UPDATED_AT_EPOCH_SECOND);
        int nano = reader.readInt(UPDATED_AT_NANO);
        reader.readRemainder();
        return new Product(id, name, price, Instant.ofEpochSecond(epochSecond, nano));
    }
}
