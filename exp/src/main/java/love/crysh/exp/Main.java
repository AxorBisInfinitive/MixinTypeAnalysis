package love.crysh.exp;

import love.crysh.exp.persistence.Database;
import love.crysh.exp.service.BusinessService;
import love.crysh.exp.web.WebServer;

import java.nio.file.Path;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("exp.port", "8080"));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("exp.port 应在 1 至 65535 之间");
        }
        Path file = Path.of(System.getProperty("exp.data", "./data/exp"));
        WebServer server = new WebServer(new BusinessService(new Database(file)), port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "exp-shutdown"));
        server.start();
        System.out.println("Exp 已启动 http://127.0.0.1:" + server.port());
        System.out.println("数据库文件 " + file.toAbsolutePath().normalize() + ".mv.db");
    }
}
