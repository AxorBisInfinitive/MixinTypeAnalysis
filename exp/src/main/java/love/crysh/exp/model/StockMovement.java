package love.crysh.exp.model;

public record StockMovement(long id, long productId, String sku, String name,
                            long quantity, long balance, String reason, Long orderId,
                            String note, String createdAt) {
}
