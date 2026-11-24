package love.crysh.exp.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import love.crysh.exp.model.OrderStatus;
import love.crysh.exp.service.BusinessException;
import love.crysh.exp.service.BusinessService;
import love.crysh.exp.service.Commands.OrderInput;
import love.crysh.exp.service.Commands.ProductInput;
import love.crysh.exp.service.Commands.StockInput;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebServer implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(WebServer.class.getName());
    private static final int MAX_BODY = 1_048_576;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final BusinessService service;
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(BigDecimal.class, (JsonSerializer<BigDecimal>)
                    (value, type, context) -> new JsonPrimitive(value.toPlainString()))
            .create();

    public WebServer(BusinessService service, int port) throws IOException {
        this.service = service;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                checkOrigin(exchange);
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/api/")) {
                    Reply reply = route(exchange, path);
                    json(exchange, reply.status(), reply.body());
                } else {
                    asset(exchange, path);
                }
            } catch (BusinessException failure) {
                json(exchange, failure.status(), Map.of("error", failure.getMessage()));
            } catch (IllegalArgumentException failure) {
                json(exchange, 400, Map.of("error", "请求参数格式不正确"));
            } catch (Exception failure) {
                LOGGER.log(System.Logger.Level.ERROR, "处理请求失败", failure);
                json(exchange, 500, Map.of("error", "服务处理失败，请查看服务端日志"));
            }
        }
    }

    private Reply route(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        String[] parts = path.substring(1).split("/", -1);
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        if (parts.length == 2) {
            return switch (parts[1]) {
                case "products" -> switch (method) {
                    case "GET" -> new Reply(200, service.products(query.get("q")));
                    case "POST" -> new Reply(201, service.createProduct(body(exchange, ProductInput.class)));
                    default -> throw method();
                };
                case "orders" -> switch (method) {
                    case "GET" -> new Reply(200, service.orders(query.get("q"), status(query.get("status"))));
                    case "POST" -> new Reply(201, service.createOrder(body(exchange, OrderInput.class)));
                    default -> throw method();
                };
                case "movements" -> {
                    requireMethod(method, "GET");
                    String id = query.get("productId");
                    yield new Reply(200, service.movements(id == null || id.isBlank() ? null : id(id)));
                }
                default -> throw notFound();
            };
        }
        if (parts.length == 3) {
            long id = id(parts[2]);
            return switch (parts[1]) {
                case "products" -> switch (method) {
                    case "GET" -> new Reply(200, service.product(id));
                    case "PUT" -> new Reply(200, service.updateProduct(id, body(exchange, ProductInput.class)));
                    default -> throw method();
                };
                case "orders" -> switch (method) {
                    case "GET" -> new Reply(200, service.order(id));
                    case "PUT" -> new Reply(200, service.updateOrder(id, body(exchange, OrderInput.class)));
                    default -> throw method();
                };
                default -> throw notFound();
            };
        }
        if (parts.length == 4) {
            requireMethod(method, "POST");
            long id = id(parts[2]);
            if (parts[1].equals("products") && parts[3].equals("restocks")) {
                return new Reply(200, service.restock(id, body(exchange, StockInput.class)));
            }
            if (parts[1].equals("orders")) {
                return new Reply(200, switch (parts[3]) {
                    case "confirm" -> service.confirmOrder(id);
                    case "cancel" -> service.cancelOrder(id);
                    case "complete" -> service.completeOrder(id);
                    default -> throw notFound();
                });
            }
        }
        throw notFound();
    }

    private <T> T body(HttpExchange exchange, Class<T> type) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            throw new BusinessException(415, "请求内容应使用 application/json");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) {
            throw new BusinessException(413, "请求内容过大");
        }
        try (JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);
            T value = gson.fromJson(reader, type);
            if (value == null || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new BusinessException(400, "请提交完整的 JSON 对象");
            }
            return value;
        } catch (JsonParseException | IllegalStateException | NumberFormatException | MalformedJsonException failure) {
            throw new BusinessException(400, "JSON 内容格式不正确");
        }
    }

    private void asset(HttpExchange exchange, String path) throws IOException {
        requireMethod(exchange.getRequestMethod(), "GET");
        String contentType = switch (path) {
            case "/" -> "text/html; charset=utf-8";
            case "/app.js" -> "text/javascript; charset=utf-8";
            case "/styles.css" -> "text/css; charset=utf-8";
            default -> throw notFound();
        };
        String resource = path.equals("/") ? "/web/index.html" : "/web" + path;
        try (InputStream input = WebServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw notFound();
            }
            send(exchange, 200, contentType, input.readAllBytes());
        }
    }

    private void json(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", gson.toJson(value).getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void checkOrigin(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (!("127.0.0.1:" + port()).equals(host) && !("localhost:" + port()).equals(host)) {
            throw new BusinessException(403, "请求地址不受支持");
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !("http://127.0.0.1:" + port()).equals(origin) && !("http://localhost:" + port()).equals(origin)) {
            throw new BusinessException(403, "请从本应用页面发起请求");
        }
    }

    private Map<String, String> query(String raw) {
        Map<String, String> parameters = new HashMap<>();
        if (raw != null) {
            for (String part : raw.split("&")) {
                String[] pair = part.split("=", 2);
                parameters.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
            }
        }
        return parameters;
    }

    private OrderStatus status(String value) {
        return value == null || value.isBlank() ? null : OrderStatus.valueOf(value);
    }

    private long id(String value) {
        long id = Long.parseLong(value);
        if (id < 1) {
            throw new BusinessException(400, "id 应为正整数");
        }
        return id;
    }

    private void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw method();
        }
    }

    private BusinessException method() {
        return new BusinessException(405, "请求方法不受支持");
    }

    private BusinessException notFound() {
        return new BusinessException(404, "页面或接口不存在");
    }

    private record Reply(int status, Object body) {
    }
}
