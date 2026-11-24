package love.crysh.exp.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import love.crysh.exp.persistence.Database;
import love.crysh.exp.service.BusinessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {
    @TempDir
    Path directory;
    private WebServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        server = new WebServer(new BusinessService(new Database(directory.resolve("http"))), 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void close() {
        client.close();
        server.close();
    }

    @Test
    void servesTheApplicationAndCompletesTheBusinessFlowOverHttp() throws Exception {
        var page = request("GET", "/", null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("订单与库存"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").isPresent());
        assertEquals(200, request("GET", "/app.js", null).statusCode());
        var product = request("POST", "/api/products", """
                {"sku":"P-001","name":"测试商品","price":"12.30","active":true}
                """);
        assertEquals(201, product.statusCode(), product.body());
        long productId = json(product).get("id").getAsLong();
        assertTrue(json(product).get("price").getAsJsonPrimitive().isString());
        assertEquals("12.30", json(product).get("price").getAsString());
        assertEquals(200, request("POST", "/api/products/" + productId + "/restocks", "{\"quantity\":10}").statusCode());
        var order = request("POST", "/api/orders", """
                {"customer":"示例客户","lines":[{"productId":%d,"quantity":3}]}
                """.formatted(productId));
        assertEquals(201, order.statusCode(), order.body());
        long orderId = json(order).getAsJsonObject("order").get("id").getAsLong();
        String path = "/api/orders/" + orderId;
        assertEquals(200, request("POST", path + "/confirm", null).statusCode());
        assertEquals(200, request("POST", path + "/confirm", null).statusCode());
        assertEquals(7, json(request("GET", "/api/products/" + productId, null)).get("stock").getAsLong());
        assertEquals(200, request("POST", path + "/cancel", null).statusCode());
        assertEquals(200, request("POST", path + "/cancel", null).statusCode());
        assertEquals(10, json(request("GET", "/api/products/" + productId, null)).get("stock").getAsLong());
        assertEquals(3, JsonParser.parseString(request("GET", "/api/movements", null).body()).getAsJsonArray().size());
        assertEquals(1, JsonParser.parseString(request("GET", "/api/orders?status=CANCELLED&q=SO-", null).body()).getAsJsonArray().size());
    }

    @Test
    void returnsUsefulErrorsAndRejectsFractionalQuantities() throws Exception {
        assertEquals(400, request("POST", "/api/products", "{").statusCode());
        assertEquals(400, request("POST", "/api/products", "null").statusCode());
        assertEquals(400, request("POST", "/api/products", "{} {}").statusCode());
        assertEquals(422, request("POST", "/api/products", "{}").statusCode());
        assertEquals(404, request("GET", "/api/products/999", null).statusCode());
        assertEquals(400, request("GET", "/api/orders?status=UNKNOWN", null).statusCode());
        assertEquals(405, request("DELETE", "/api/products", null).statusCode());
        var product = request("POST", "/api/products", "{\"sku\":\"P\",\"name\":\"商品\",\"price\":\"1.00\"}");
        long id = json(product).get("id").getAsLong();
        var fraction = request("POST", "/api/products/" + id + "/restocks", "{\"quantity\":1.5}");
        assertEquals(400, fraction.statusCode(), fraction.body());
        assertEquals(422, request("POST", "/api/products/" + id + "/restocks", "{\"quantity\":-1}").statusCode());
        assertEquals(0, json(request("GET", "/api/products/" + id, null)).get("stock").getAsLong());
        var crossOrigin = HttpRequest.newBuilder(URI.create(base + "/api/products"))
                .header("Origin", "https://example.com").GET().build();
        assertEquals(403, client.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
