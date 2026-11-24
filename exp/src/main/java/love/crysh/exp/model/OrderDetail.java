package love.crysh.exp.model;

import java.util.List;

public record OrderDetail(SalesOrder order, List<OrderLine> lines) {
    public OrderDetail {
        lines = List.copyOf(lines);
    }
}
