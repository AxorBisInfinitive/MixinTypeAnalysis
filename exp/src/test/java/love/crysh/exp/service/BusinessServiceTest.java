package love.crysh.exp.service;

import love.crysh.exp.model.OrderDetail;
import love.crysh.exp.model.OrderStatus;
import love.crysh.exp.model.Product;
import love.crysh.exp.persistence.Database;
import love.crysh.exp.service.Commands.LineInput;
import love.crysh.exp.service.Commands.OrderInput;
import love.crysh.exp.service.Commands.ProductInput;
import love.crysh.exp.service.Commands.StockInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BusinessServiceTest {
    @TempDir
    Path directory;
    private BusinessService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new BusinessService(new Database(directory.resolve("business")));
    }

    @Test
    void confirmsCancelsAndRetainsDataAcrossRestart() throws Exception {
        Product product = product("P-001", "12.30", 10);
        OrderDetail order = order(product, 3);
        assertEquals(new BigDecimal("36.90"), order.order().total());
        service.confirmOrder(order.order().id());
        service.confirmOrder(order.order().id());
        assertEquals(7, service.product(product.id()).stock());
        service.cancelOrder(order.order().id());
        service.cancelOrder(order.order().id());
        assertEquals(10, service.product(product.id()).stock());
        assertEquals(3, service.movements(product.id()).size());

        BusinessService restarted = new BusinessService(new Database(directory.resolve("business")));
        assertEquals(OrderStatus.CANCELLED, restarted.order(order.order().id()).order().status());
        assertEquals(10, restarted.product(product.id()).stock());
        assertEquals(3, restarted.movements(product.id()).size());
    }

    @Test
    void rollsBackEveryLineWhenOneProductHasInsufficientStock() {
        Product first = product("A", "10.00", 5);
        Product second = product("B", "10.00", 1);
        OrderDetail order = service.createOrder(new OrderInput("客户", List.of(
                new LineInput(first.id(), 3), new LineInput(second.id(), 2))));
        assertThrows(BusinessException.class, () -> service.confirmOrder(order.order().id()));
        assertEquals(5, service.product(first.id()).stock());
        assertEquals(1, service.product(second.id()).stock());
        assertEquals(2, service.movements(null).size());
        assertEquals(OrderStatus.DRAFT, service.order(order.order().id()).order().status());
    }

    @Test
    void preservesPriceAndNameSnapshotsAndCompletedOrders() {
        Product product = product("P-001", "0.10", 10);
        OrderDetail order = order(product, 3);
        service.updateProduct(product.id(), new ProductInput("P-002", "新名称", new BigDecimal("99.99"), true));
        service.confirmOrder(order.order().id());
        service.completeOrder(order.order().id());
        service.completeOrder(order.order().id());
        OrderDetail saved = service.order(order.order().id());
        assertEquals(new BigDecimal("0.30"), saved.order().total());
        assertEquals("P-001", saved.lines().getFirst().sku());
        assertEquals("商品 P-001", saved.lines().getFirst().name());
        assertEquals(7, service.product(product.id()).stock());
        assertThrows(BusinessException.class, () -> service.cancelOrder(order.order().id()));
        assertThrows(BusinessException.class, () -> service.updateOrder(order.order().id(), new OrderInput("新客户", List.of(new LineInput(product.id(), 1)))));
    }

    @Test
    void editsDraftsAndCancelsThemWithoutStockChanges() {
        Product product = product("P", "1.25", 10);
        OrderDetail order = order(product, 1);
        OrderDetail updated = service.updateOrder(order.order().id(), new OrderInput("新客户", List.of(new LineInput(product.id(), 4))));
        assertEquals("新客户", updated.order().customer());
        assertEquals(new BigDecimal("5.00"), updated.order().total());
        service.cancelOrder(order.order().id());
        assertEquals(10, service.product(product.id()).stock());
        assertEquals(1, service.movements(null).size());
        assertThrows(BusinessException.class, () -> service.confirmOrder(order.order().id()));
    }

    @Test
    void rejectsInvalidInputsAndDisabledProducts() {
        Product product = product("P", "1.00", 0);
        assertThrows(BusinessException.class, () -> product("P", "1.00", 0));
        assertThrows(BusinessException.class, () -> product("X", "1.001", 0));
        assertThrows(BusinessException.class, () -> service.restock(product.id(), new StockInput(-1, "")));
        assertThrows(BusinessException.class, () -> service.createOrder(new OrderInput("客户", List.of())));
        assertThrows(BusinessException.class, () -> service.createOrder(new OrderInput("客户", List.of(
                new LineInput(product.id(), 1), new LineInput(product.id(), 2)))));
        OrderDetail order = order(product, 1);
        service.updateProduct(product.id(), new ProductInput(product.sku(), product.name(), product.price(), false));
        assertThrows(BusinessException.class, () -> service.confirmOrder(order.order().id()));
        assertThrows(BusinessException.class, () -> order(product, 1));
        assertEquals(OrderStatus.DRAFT, service.order(order.order().id()).order().status());
    }

    @Test
    void confirmsTheSameOrderConcurrentlyOnlyOnce() throws Exception {
        Product product = product("P", "1.00", 10);
        OrderDetail order = order(product, 3);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return service.confirmOrder(order.order().id()); });
            var second = executor.submit(() -> { start.await(); return service.confirmOrder(order.order().id()); });
            start.countDown();
            assertEquals(OrderStatus.CONFIRMED, first.get(15, TimeUnit.SECONDS).order().status());
            assertEquals(OrderStatus.CONFIRMED, second.get(15, TimeUnit.SECONDS).order().status());
        }
        assertEquals(7, service.product(product.id()).stock());
        assertEquals(2, service.movements(null).size());
    }

    @Test
    void competingOrdersCannotOversell() throws Exception {
        Product product = product("P", "1.00", 5);
        OrderDetail first = order(product, 3);
        OrderDetail second = order(product, 3);
        AtomicInteger successes = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = List.of(first, second).stream().map(order -> executor.submit(() -> {
                start.await();
                try {
                    service.confirmOrder(order.order().id());
                    successes.incrementAndGet();
                } catch (BusinessException failure) {
                    assertEquals(409, failure.status());
                }
                return null;
            })).toList();
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, successes.get());
        assertEquals(2, service.product(product.id()).stock());
        assertEquals(2, service.movements(null).size());
    }

    private Product product(String sku, String price, int stock) {
        Product product = service.createProduct(new ProductInput(sku, "商品 " + sku, new BigDecimal(price), true));
        if (stock > 0) service.restock(product.id(), new StockInput(stock, "采购入库"));
        return product;
    }

    private OrderDetail order(Product product, int quantity) {
        return service.createOrder(new OrderInput("测试客户", List.of(new LineInput(product.id(), quantity))));
    }
}
