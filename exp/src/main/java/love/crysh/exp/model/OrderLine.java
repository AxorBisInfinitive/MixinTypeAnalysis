package love.crysh.exp.model;

import java.math.BigDecimal;

public record OrderLine(long productId, String sku, String name, BigDecimal unitPrice,
                        int quantity, BigDecimal subtotal) {
}
