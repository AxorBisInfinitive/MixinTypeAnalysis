package love.crysh.exp.service;

import java.math.BigDecimal;
import java.util.List;

public final class Commands {
    private Commands() {
    }

    public record ProductInput(String sku, String name, BigDecimal price, Boolean active) {
    }

    public record StockInput(int quantity, String note) {
    }

    public record LineInput(long productId, int quantity) {
    }

    public record OrderInput(String customer, List<LineInput> lines) {
    }
}
