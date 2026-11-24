# Exp

本地单用户的订单与库存管理系统，基础包为 `love.crysh.exp`。

使用 JDK 25、Maven、H2、JDK HttpServer 和 JUnit 5。

## 构建与运行

在仓库根目录执行。

```powershell
mvn -f exp/pom.xml clean verify
java -jar exp/target/exp-0.1.0-Snapshot.jar
```

浏览器访问 <http://127.0.0.1:8080>。

数据库默认保存在进程工作目录下的 `data/exp.mv.db`。关闭应用后保留数据，再次启动时继续使用同一文件。

端口和数据库路径可以通过 JVM 属性设置。`exp.data` 指定文件名前缀。

```powershell
java -Dexp.port=8081 -Dexp.data=./data/shop -jar exp/target/exp-0.1.0-Snapshot.jar
```

## 业务流程

1. 在商品管理中创建商品，填写编号、名称和售价。
2. 在库存管理中登记入库数量。
3. 创建订单草稿，选择客户、商品和数量。
4. 确认订单时扣减库存，记录每件商品的库存流水。
5. 取消已确认订单时返还库存。已完成订单保留成交记录。

草稿可以编辑。重复确认、取消和完成具有幂等语义。订单变更、库存变更和流水写入共同提交，库存不足时整单回滚。

订单明细保存商品编号、名称和单价的快照。保存草稿时按当前商品资料更新快照，确认后的明细保留成交数据。金额以两位小数计算，HTTP 响应中的金额使用十进制字符串。

## HTTP 接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET、POST | `/api/products` | 查询、新增商品 |
| GET、PUT | `/api/products/{id}` | 查询、编辑商品 |
| POST | `/api/products/{id}/restocks` | 商品入库 |
| GET | `/api/movements` | 查询库存流水 |
| GET、POST | `/api/orders` | 查询、新建订单 |
| GET、PUT | `/api/orders/{id}` | 查询、编辑草稿 |
| POST | `/api/orders/{id}/confirm` | 确认订单 |
| POST | `/api/orders/{id}/cancel` | 取消订单 |
| POST | `/api/orders/{id}/complete` | 完成订单 |

商品和订单列表支持 `q` 查询。订单列表另支持 `status`，取值为 `DRAFT`、`CONFIRMED`、`COMPLETED`、`CANCELLED`。库存流水支持 `productId`。

新增商品的请求体示例。

```json
{"sku":"P-001","name":"笔记本","price":"12.30","active":true}
```

入库的请求体示例。

```json
{"quantity":10,"note":"采购入库"}
```

新建订单的请求体示例。

```json
{"customer":"示例客户","lines":[{"productId":1,"quantity":3}]}
```

请求体使用 `application/json`。接口错误返回 `error` 字段，参数格式错误使用 400，数据校验失败使用 422，记录不存在使用 404，状态冲突使用 409。

商品编号区分大小写。售价范围为 0 至 99999999.99，每次入库和每条订单明细的数量范围为 1 至 1000000，每种商品的库存上限为 1000000000。每笔订单最多包含 100 种商品。
