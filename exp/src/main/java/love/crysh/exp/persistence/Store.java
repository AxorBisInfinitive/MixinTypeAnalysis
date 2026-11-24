package love.crysh.exp.persistence;

import love.crysh.exp.model.OrderLine;
import love.crysh.exp.model.OrderStatus;
import love.crysh.exp.model.Product;
import love.crysh.exp.model.SalesOrder;
import love.crysh.exp.model.StockMovement;
import love.crysh.exp.service.BusinessException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Store {
    public List<Product> products(Connection connection, String query) throws SQLException {
        String sql = "SELECT * FROM products WHERE LOCATE(?, LOWER(sku)) > 0 OR LOCATE(?, LOWER(name)) > 0 ORDER BY id DESC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String search = query.toLowerCase(Locale.ROOT);
            statement.setString(1, search);
            statement.setString(2, search);
            try (ResultSet rows = statement.executeQuery()) {
                List<Product> products = new ArrayList<>();
                while (rows.next()) {
                    products.add(product(rows));
                }
                return products;
            }
        }
    }

    public Product product(Connection connection, long id, boolean lock) throws SQLException {
        String sql = "SELECT * FROM products WHERE id = ?";
        if (lock) {
            sql += " FOR UPDATE";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new BusinessException(404, "商品不存在");
                }
                return product(rows);
            }
        }
    }

    public long insertProduct(Connection connection, String sku, String name, BigDecimal price, boolean active) throws SQLException {
        String sql = "INSERT INTO products(sku, name, price, active) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, sku);
            statement.setString(2, name);
            statement.setBigDecimal(3, price);
            statement.setBoolean(4, active);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    public void updateProduct(Connection connection, long id, String sku, String name, BigDecimal price, boolean active) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE products SET sku = ?, name = ?, price = ?, active = ? WHERE id = ?")) {
            statement.setString(1, sku);
            statement.setString(2, name);
            statement.setBigDecimal(3, price);
            statement.setBoolean(4, active);
            statement.setLong(5, id);
            statement.executeUpdate();
        }
    }

    public void stock(Connection connection, long productId, long balance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE products SET stock = ? WHERE id = ?")) {
            statement.setLong(1, balance);
            statement.setLong(2, productId);
            statement.executeUpdate();
        }
    }

    public void movement(Connection connection, long productId, long quantity, long balance, String reason, Long orderId, String note) throws SQLException {
        String sql = "INSERT INTO stock_movements(product_id, quantity, balance, reason, order_id, note) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, productId);
            statement.setLong(2, quantity);
            statement.setLong(3, balance);
            statement.setString(4, reason);
            statement.setObject(5, orderId);
            statement.setString(6, note);
            statement.executeUpdate();
        }
    }

    public List<StockMovement> movements(Connection connection, Long productId) throws SQLException {
        String sql = """
                SELECT m.*, p.sku, p.name FROM stock_movements m
                JOIN products p ON p.id = m.product_id
                WHERE (? IS NULL OR m.product_id = ?) ORDER BY m.id DESC
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, productId);
            statement.setObject(2, productId);
            try (ResultSet rows = statement.executeQuery()) {
                List<StockMovement> movements = new ArrayList<>();
                while (rows.next()) {
                    movements.add(new StockMovement(rows.getLong("id"), rows.getLong("product_id"),
                            rows.getString("sku"), rows.getString("name"), rows.getLong("quantity"),
                            rows.getLong("balance"), rows.getString("reason"), (Long) rows.getObject("order_id"),
                            rows.getString("note"), time(rows, "created_at")));
                }
                return movements;
            }
        }
    }

    public List<SalesOrder> orders(Connection connection, String query, OrderStatus status) throws SQLException {
        String sql = """
                SELECT * FROM sales_orders
                WHERE (LOCATE(?, LOWER(customer)) > 0 OR LOCATE(?, LOWER(CONCAT('SO-', id))) > 0)
                  AND (? IS NULL OR status = ?) ORDER BY id DESC
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String search = query.toLowerCase(Locale.ROOT);
            statement.setString(1, search);
            statement.setString(2, search);
            String filter = status == null ? null : status.name();
            statement.setString(3, filter);
            statement.setString(4, filter);
            try (ResultSet rows = statement.executeQuery()) {
                List<SalesOrder> orders = new ArrayList<>();
                while (rows.next()) {
                    orders.add(order(rows));
                }
                return orders;
            }
        }
    }

    public SalesOrder order(Connection connection, long id, boolean lock) throws SQLException {
        String sql = "SELECT * FROM sales_orders WHERE id = ?";
        if (lock) {
            sql += " FOR UPDATE";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new BusinessException(404, "订单不存在");
                }
                return order(rows);
            }
        }
    }

    public List<OrderLine> lines(Connection connection, long orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM order_lines WHERE order_id = ? ORDER BY product_id")) {
            statement.setLong(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                List<OrderLine> lines = new ArrayList<>();
                while (rows.next()) {
                    lines.add(new OrderLine(rows.getLong("product_id"), rows.getString("sku"),
                            rows.getString("name"), rows.getBigDecimal("unit_price"),
                            rows.getInt("quantity"), rows.getBigDecimal("subtotal")));
                }
                return lines;
            }
        }
    }

    public long insertOrder(Connection connection, String customer, BigDecimal total) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO sales_orders(customer, status, total) VALUES (?, 'DRAFT', ?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, customer);
            statement.setBigDecimal(2, total);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    public void updateDraft(Connection connection, long id, String customer, BigDecimal total) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE sales_orders SET customer = ?, total = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            statement.setString(1, customer);
            statement.setBigDecimal(2, total);
            statement.setLong(3, id);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM order_lines WHERE order_id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    public void insertLines(Connection connection, long orderId, List<OrderLine> lines) throws SQLException {
        String sql = "INSERT INTO order_lines(order_id, product_id, sku, name, unit_price, quantity, subtotal) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (OrderLine line : lines) {
                statement.setLong(1, orderId);
                statement.setLong(2, line.productId());
                statement.setString(3, line.sku());
                statement.setString(4, line.name());
                statement.setBigDecimal(5, line.unitPrice());
                statement.setInt(6, line.quantity());
                statement.setBigDecimal(7, line.subtotal());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public void status(Connection connection, long id, OrderStatus status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE sales_orders SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            statement.setString(1, status.name());
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    private long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("数据库未返回新增记录的 id");
            }
            return keys.getLong(1);
        }
    }

    private Product product(ResultSet rows) throws SQLException {
        return new Product(rows.getLong("id"), rows.getString("sku"), rows.getString("name"),
                rows.getBigDecimal("price"), rows.getLong("stock"), rows.getBoolean("active"), time(rows, "created_at"));
    }

    private SalesOrder order(ResultSet rows) throws SQLException {
        long id = rows.getLong("id");
        return new SalesOrder(id, "SO-" + id, rows.getString("customer"), OrderStatus.valueOf(rows.getString("status")),
                rows.getBigDecimal("total"), time(rows, "created_at"), time(rows, "updated_at"));
    }

    private String time(ResultSet rows, String column) throws SQLException {
        return rows.getObject(column, OffsetDateTime.class).toInstant().toString();
    }
}
