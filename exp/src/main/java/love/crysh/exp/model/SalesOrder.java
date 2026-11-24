package love.crysh.exp.model;

import java.math.BigDecimal;

public record SalesOrder(long id, String number, String customer, OrderStatus status,
                         BigDecimal total, String createdAt, String updatedAt) {
}
