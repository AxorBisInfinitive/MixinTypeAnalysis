package love.crysh.exp.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    private final String url;

    public Database(Path file) throws IOException, SQLException {
        Path absolute = file.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        url = "jdbc:h2:file:" + absolute.toString().replace('\\', '/') + ";LOCK_TIMEOUT=10000";
        try (Connection connection = open();
             InputStream input = Database.class.getResourceAsStream("/schema.sql")) {
            if (input == null) {
                throw new IOException("缺少 schema.sql");
            }
            String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement statement = connection.createStatement()) {
                for (String sql : schema.split(";")) {
                    if (!sql.isBlank()) {
                        statement.execute(sql);
                    }
                }
            }
        }
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    /** 回调中的写入共同提交。失败时回滚，避免订单与库存只保存一部分。 */
    public <T> T transaction(Work<T> work) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    @FunctionalInterface
    public interface Work<T> {
        T execute(Connection connection) throws SQLException;
    }
}
