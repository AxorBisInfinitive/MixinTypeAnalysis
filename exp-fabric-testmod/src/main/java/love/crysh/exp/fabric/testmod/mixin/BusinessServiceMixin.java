package love.crysh.exp.fabric.testmod.mixin;

import java.sql.Connection;
import java.sql.SQLException;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import love.crysh.exp.model.Product;
import love.crysh.exp.persistence.Store;
import love.crysh.exp.service.BusinessService;

@Mixin(value = BusinessService.class, remap = false)
public abstract class BusinessServiceMixin {
    @Shadow
    @Final
    private Store store;

    @Shadow
    private void changeStock(Connection connection, Product product, long quantity, String reason,
            Long orderId, String note) throws SQLException {
    }

    @Redirect(method = "lambda$confirmOrder$0(JLjava/sql/Connection;)Llove/crysh/exp/model/OrderDetail;",
            at = @At(value = "INVOKE", target = "Llove/crysh/exp/service/BusinessService;changeStock(Ljava/sql/Connection;Llove/crysh/exp/model/Product;JLjava/lang/String;Ljava/lang/Long;Ljava/lang/String;)V"),
            require = 1)
    private void test$replenishOnInsufficientStock(BusinessService instance, Connection connection,
            Product product, long quantity, String reason,
            Long orderId, String note) throws SQLException {
        if ("ORDER_CONFIRMED".equals(reason) && product.stock() + quantity < 0) {
            long replenishedStock = product.stock() + 10_000;
            store.stock(connection, product.id(), replenishedStock);
            store.movement(connection, product.id(), 10_000, replenishedStock,
                    "RESTOCK", null, "订单确认时库存不足，自动补货 10000 件");
            product = new Product(product.id(), product.sku(), product.name(), product.price(),
                    replenishedStock, product.active(), product.createdAt());
        }

        changeStock(connection, product, quantity, reason, orderId, note);
    }
}
