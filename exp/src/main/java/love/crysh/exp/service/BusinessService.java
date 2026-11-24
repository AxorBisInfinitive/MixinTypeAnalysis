package love.crysh.exp.service;

import love.crysh.exp.model.OrderDetail;
import love.crysh.exp.model.OrderLine;
import love.crysh.exp.model.OrderStatus;
import love.crysh.exp.model.Product;
import love.crysh.exp.model.SalesOrder;
import love.crysh.exp.model.StockMovement;
import love.crysh.exp.persistence.Database;
import love.crysh.exp.persistence.Store;
import love.crysh.exp.service.Commands.LineInput;
import love.crysh.exp.service.Commands.OrderInput;
import love.crysh.exp.service.Commands.ProductInput;
import love.crysh.exp.service.Commands.StockInput;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public class BusinessService {
    private static final long MAX_STOCK = 1_000_000_000;
    private final Database database;
    private final Store store = new Store();

    public BusinessService(Database database) {
        this.database = database;
    }

    public List<Product> products(String query) {
        return transact(connection -> store.products(connection, search(query)));
    }

    public Product product(long id) {
        positiveId(id);
        return transact(connection -> store.product(connection, id, false));
    }

    public Product createProduct(ProductInput input) {
        requireBody(input);
        String sku = text(input.sku(), "商品编号", 64);
        String name = text(input.name(), "商品名称", 120);
        BigDecimal price = price(input.price());
        boolean active = input.active() == null || input.active();
        return transact(connection -> {
            long id = store.insertProduct(connection, sku, name, price, active);
            return store.product(connection, id, false);
        });
    }

    public Product updateProduct(long id, ProductInput input) {
        positiveId(id);
        requireBody(input);
        String sku = text(input.sku(), "商品编号", 64);
        String name = text(input.name(), "商品名称", 120);
        BigDecimal price = price(input.price());
        return transact(connection -> {
            Product existing = store.product(connection, id, true);
            boolean active = input.active() == null ? existing.active() : input.active();
            store.updateProduct(connection, id, sku, name, price, active);
            return store.product(connection, id, false);
        });
    }

    public Product restock(long id, StockInput input) {
        positiveId(id);
        requireBody(input);
        quantity(input.quantity());
        String note = input.note() == null ? "" : input.note().strip();
        if (note.length() > 200) {
            throw invalid("入库备注最多 200 个字符");
        }
        return transact(connection -> {
            Product product = store.product(connection, id, true);
            changeStock(connection, product, input.quantity(), "RESTOCK", null, note);
            return store.product(connection, id, false);
        });
    }

    public List<StockMovement> movements(Long productId) {
        if (productId != null) {
            positiveId(productId);
        }
        return transact(connection -> store.movements(connection, productId));
    }

    public List<SalesOrder> orders(String query, OrderStatus status) {
        return transact(connection -> store.orders(connection, search(query), status));
    }

    public OrderDetail order(long id) {
        positiveId(id);
        return transact(connection -> {
            SalesOrder order = store.order(connection, id, true);
            return new OrderDetail(order, store.lines(connection, id));
        });
    }

    public OrderDetail createOrder(OrderInput input) {
        String customer = validateOrder(input);
        return transact(connection -> {
            List<OrderLine> lines = snapshot(connection, input.lines());
            long id = store.insertOrder(connection, customer, total(lines));
            store.insertLines(connection, id, lines);
            return detail(connection, id);
        });
    }

    public OrderDetail updateOrder(long id, OrderInput input) {
        positiveId(id);
        String customer = validateOrder(input);
        return transact(connection -> {
            SalesOrder order = store.order(connection, id, true);
            if (order.status() != OrderStatus.DRAFT) {
                throw conflict("仅草稿订单可以编辑");
            }
            List<OrderLine> lines = snapshot(connection, input.lines());
            store.updateDraft(connection, id, customer, total(lines));
            store.insertLines(connection, id, lines);
            return detail(connection, id);
        });
    }

    /** 先锁定订单，再按 productId 升序锁定商品，使并发订单遵循相同的加锁顺序。 */
    public OrderDetail confirmOrder(long id) {
        positiveId(id);
        return transact(connection -> {
            SalesOrder order = store.order(connection, id, true);
            if (order.status() == OrderStatus.CONFIRMED) {
                return detail(connection, id);
            }
            if (order.status() != OrderStatus.DRAFT) {
                throw conflict("只有草稿订单可以确认");
            }
            for (OrderLine line : store.lines(connection, id)) {
                Product product = store.product(connection, line.productId(), true);
                if (!product.active()) {
                    throw conflict("商品已停用：" + product.name());
                }
                changeStock(connection, product, -line.quantity(), "ORDER_CONFIRMED", id, "确认订单 " + order.number());
            }
            store.status(connection, id, OrderStatus.CONFIRMED);
            return detail(connection, id);
        });
    }

    public OrderDetail cancelOrder(long id) {
        positiveId(id);
        return transact(connection -> {
            SalesOrder order = store.order(connection, id, true);
            if (order.status() == OrderStatus.CANCELLED) {
                return detail(connection, id);
            }
            if (order.status() == OrderStatus.COMPLETED) {
                throw conflict("已完成的订单无法取消");
            }
            if (order.status() == OrderStatus.CONFIRMED) {
                for (OrderLine line : store.lines(connection, id)) {
                    Product product = store.product(connection, line.productId(), true);
                    changeStock(connection, product, line.quantity(), "ORDER_CANCELLED", id, "取消订单 " + order.number());
                }
            }
            store.status(connection, id, OrderStatus.CANCELLED);
            return detail(connection, id);
        });
    }

    public OrderDetail completeOrder(long id) {
        positiveId(id);
        return transact(connection -> {
            SalesOrder order = store.order(connection, id, true);
            if (order.status() == OrderStatus.COMPLETED) {
                return detail(connection, id);
            }
            if (order.status() != OrderStatus.CONFIRMED) {
                throw conflict("只有已确认的订单可以完成");
            }
            store.status(connection, id, OrderStatus.COMPLETED);
            return detail(connection, id);
        });
    }

    private void changeStock(Connection connection, Product product, long quantity, String reason, Long orderId, String note) throws SQLException {
        long balance = product.stock() + quantity;
        if (balance < 0) {
            throw conflict("商品库存不足：" + product.name());
        }
        if (balance > MAX_STOCK) {
            throw conflict("商品库存最多为 " + MAX_STOCK);
        }
        store.stock(connection, product.id(), balance);
        store.movement(connection, product.id(), quantity, balance, reason, orderId, note);
    }

    private List<OrderLine> snapshot(Connection connection, List<LineInput> inputs) throws SQLException {
        List<LineInput> sorted = inputs.stream().sorted(Comparator.comparingLong(LineInput::productId)).toList();
        List<OrderLine> lines = new ArrayList<>();
        for (LineInput input : sorted) {
            Product product = store.product(connection, input.productId(), true);
            if (!product.active()) {
                throw conflict("商品已停用：" + product.name());
            }
            BigDecimal subtotal = product.price().multiply(BigDecimal.valueOf(input.quantity()));
            lines.add(new OrderLine(product.id(), product.sku(), product.name(), product.price(), input.quantity(), subtotal));
        }
        return lines;
    }

    private BigDecimal total(List<OrderLine> lines) {
        return lines.stream().map(OrderLine::subtotal).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    private OrderDetail detail(Connection connection, long id) throws SQLException {
        return new OrderDetail(store.order(connection, id, false), store.lines(connection, id));
    }

    private String validateOrder(OrderInput input) {
        requireBody(input);
        String customer = text(input.customer(), "客户名称", 120);
        if (input.lines() == null || input.lines().isEmpty() || input.lines().size() > 100) {
            throw invalid("订单需要包含 1 至 100 种商品");
        }
        HashSet<Long> ids = new HashSet<>();
        for (LineInput line : input.lines()) {
            requireBody(line);
            positiveId(line.productId());
            quantity(line.quantity());
            if (!ids.add(line.productId())) {
                throw invalid("同一商品只能添加一次，请修改该商品的数量");
            }
        }
        return customer;
    }

    private BigDecimal price(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("99999999.99")) > 0) {
            throw invalid("售价应在 0 至 99999999.99 之间");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw invalid("售价最多保留两位小数");
        }
    }

    private void quantity(int value) {
        if (value < 1 || value > 1_000_000) {
            throw invalid("数量应为 1 至 1000000 的整数");
        }
    }

    private void positiveId(long id) {
        if (id < 1) {
            throw invalid("id 应为正整数");
        }
    }

    private String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw invalid(field + "需要填写 1 至 " + max + " 个字符");
        }
        return value.strip();
    }

    private String search(String query) {
        if (query == null) {
            return "";
        }
        if (query.length() > 200) {
            throw invalid("查询内容最多 200 个字符");
        }
        return query.strip();
    }

    private void requireBody(Object input) {
        if (input == null) {
            throw invalid("请填写完整的请求内容");
        }
    }

    private <T> T transact(Database.Work<T> work) {
        try {
            return database.transaction(work);
        } catch (SQLException failure) {
            if ("23505".equals(failure.getSQLState())) {
                throw conflict("商品编号已存在，请使用其他编号");
            }
            if ("40001".equals(failure.getSQLState()) || failure.getErrorCode() == 50200) {
                throw conflict("记录正在被其他操作修改，请重试");
            }
            throw new IllegalStateException("数据库操作失败", failure);
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(422, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }
}
