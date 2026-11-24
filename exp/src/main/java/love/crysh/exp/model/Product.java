package love.crysh.exp.model;

import java.math.BigDecimal;

public record Product(long id, String sku, String name, BigDecimal price, long stock,
                      boolean active, String createdAt) {
}
