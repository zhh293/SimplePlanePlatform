# Task-1：HttpRequestParser —— HTTP 请求解析与 URL 重写工具类

> **所属 SDD**：[http-plain-proxy-design.md](./http-plain-proxy-design.md)
> **任务编号**：Task-1 / 3
> **预计产出**：`HttpRequestParser.java` + 编译通过
> **前置依赖**：无
> **后续依赖**：Task-2（HttpProxyRelayHandler）、Task-3（HttpConnectHandler 改造）都依赖本类

---

## 1. 任务目标

实现一个纯工具类 `HttpRequestParser`，职责单一：从浏览器发给代理的原始 HTTP 请求字节中解析出目标 host:port，并将绝对 URL 重写为相对路径格式。不涉及任何网络操作、Netty API 或 pipeline 逻辑。

## 2. 文件位置

```
proxy-local/src/main/java/com/proxy/local/handler/HttpRequestParser.java
```

## 3. 实现思路

### 3.1 数据流

```
输入: byte[] rawRequest（浏览器发来的原始 HTTP 请求头字节，以 \r\n\r\n 结尾）
  │
  ├─ 步骤 1: byte[] → String（UTF-8 解码，仅处理请求行和 header）
  │
  ├─ 步骤 2: 提取请求行，拆分为 method、absoluteUrl、httpVersion
  │          例如: "GET", "http://example.com:8080/page?q=1", "HTTP/1.1"
  │
  ├─ 步骤 3: 解析 absoluteUrl
  │          ├─ scheme: "http"
  │          ├─ host: "example.com"
  │          ├─ port: 8080（无则默认 80）
  │          └─ pathAndQuery: "/page?q=1"（无路径则默认 "/"）
  │
  ├─ 步骤 4: 重写请求行
  │          "GET http://example.com:8080/page?q=1 HTTP/1.1"
  │          → "GET /page?q=1 HTTP/1.1"
  │
  ├─ 步骤 5: 过滤 Proxy-Connection 头（如果存在则移除整行）
  │
  └─ 步骤 6: 重新拼接为 byte[]，返回 ParseResult(host, port, rewrittenBytes)

输出: ParseResult { host="example.com", port=8080, rewritten=byte[] }
```

### 3.2 URL 解析算法

不使用 `java.net.URL`（它会尝试 DNS 解析），改用纯字符串操作：

```
absoluteUrl = "http://example.com:8080/page?q=1"

1. 检查并去掉 "http://" 前缀
   → "example.com:8080/page?q=1"

2. 找到第一个 "/" 的位置，分离 hostPort 和 pathAndQuery
   → hostPort = "example.com:8080"
   → pathAndQuery = "/page?q=1"
   （如果没有 "/"，pathAndQuery = "/"）

3. 在 hostPort 中找 ":"，分离 host 和 port
   → host = "example.com"
   → port = 8080
   （如果没有 ":"，port = 80）
```

### 3.3 Fallback 到 Host 头

当请求行中的 URL 不是绝对 URL（不以 `http://` 开头）时，从 Header 中查找 `Host:` 字段：

```
Host: example.com       → host="example.com", port=80
Host: example.com:8080  → host="example.com", port=8080
```

此场景主要兜底一些非标准的 HTTP 客户端。

### 3.4 Proxy-Connection 头过滤

逐行扫描 header，遇到以 `proxy-connection:` 开头（不区分大小写）的行，跳过不写入输出。这个头是浏览器和代理之间的约定，不应转发给目标服务器。

### 3.5 核心类结构

```java
public class HttpRequestParser {

    private static final String HTTP_SCHEME_PREFIX = "http://";
    private static final int DEFAULT_HTTP_PORT = 80;

    /**
     * 解析结果：不可变对象
     */
    public static class ParseResult {
        private final String host;
        private final int port;
        private final byte[] rewritten;

        public ParseResult(String host, int port, byte[] rewritten) { ... }
        public String getHost() { ... }
        public int getPort() { ... }
        public byte[] getRewritten() { ... }
    }

    /**
     * 解析并重写 HTTP 代理请求。
     * @param rawRequest 原始请求头字节（包含 \r\n\r\n）
     * @return 解析结果
     * @throws IllegalArgumentException 无法解析时抛出
     */
    public static ParseResult parse(byte[] rawRequest) { ... }

    // ---- 内部方法 ----

    /** 从请求行中解析绝对 URL 的 host:port 和 pathAndQuery */
    private static ... parseAbsoluteUrl(String url) { ... }

    /** 从 Header 区域解析 Host 头作为 fallback */
    private static ... parseHostHeader(String headers) { ... }

    /** 移除 Proxy-Connection 头 */
    private static String removeProxyConnectionHeader(String headers) { ... }
}
```

## 4. 边界情况处理

| 输入 | 预期行为 |
|------|---------|
| `GET http://example.com/ HTTP/1.1` | host=example.com, port=80, path=`/` |
| `GET http://example.com HTTP/1.1` | host=example.com, port=80, path=`/`（无路径补 `/`） |
| `POST http://example.com:8080/api HTTP/1.1` | host=example.com, port=8080, path=`/api` |
| `GET http://example.com/path?q=a&b=c HTTP/1.1` | path=`/path?q=a&b=c`（query 保留） |
| `GET /relative/path HTTP/1.1` + `Host: example.com` | fallback 到 Host 头解析 |
| `GET /relative/path HTTP/1.1`（无 Host 头） | 抛出 IllegalArgumentException |
| 请求包含 `Proxy-Connection: keep-alive` | 输出中移除该头 |
| `null` 或空数组 | 抛出 IllegalArgumentException |
| 请求行不足 3 段 | 抛出 IllegalArgumentException |

## 5. 测试方案

### 5.1 单元测试方法

本类是纯工具类，不依赖 Netty、不依赖网络，可以直接用 JUnit 单元测试。每个测试用例构造一个 `byte[]` 输入，调用 `HttpRequestParser.parse()`，验证返回的 `ParseResult`。

### 5.2 测试用例清单

#### 正常场景

```java
// TC-1: 标准 GET 请求，绝对 URL，默认端口
@Test
void testStandardGet() {
    byte[] input = "GET http://example.com/page HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes();
    ParseResult result = HttpRequestParser.parse(input);
    assertEquals("example.com", result.getHost());
    assertEquals(80, result.getPort());
    // 验证重写后请求行是 "GET /page HTTP/1.1"
    String rewritten = new String(result.getRewritten());
    assertTrue(rewritten.startsWith("GET /page HTTP/1.1\r\n"));
}

// TC-2: 带自定义端口
@Test
void testCustomPort() {
    byte[] input = "GET http://example.com:8080/api HTTP/1.1\r\nHost: example.com:8080\r\n\r\n".getBytes();
    ParseResult result = HttpRequestParser.parse(input);
    assertEquals("example.com", result.getHost());
    assertEquals(8080, result.getPort());
}

// TC-3: 无路径，自动补 "/"
@Test
void testNoPath() {
    byte[] input = "GET http://example.com HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes();
    ParseResult result = HttpRequestParser.parse(input);
    String rewritten = new String(result.getRewritten());
    assertTrue(rewritten.startsWith("GET / HTTP/1.1\r\n"));
}

// TC-4: 带 query string
@Test
void testWithQueryString() {
    byte[] input = "GET http://example.com/search?q=hello&lang=zh HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes();
    ParseResult result = HttpRequestParser.parse(input);
    String rewritten = new String(result.getRewritten());
    assertTrue(rewritten.startsWith("GET /search?q=hello&lang=zh HTTP/1.1\r\n"));
}

// TC-5: POST 方法
@Test
void testPostMethod() {
    byte[] input = "POST http://example.com/api/data HTTP/1.1\r\nHost: example.com\r\nContent-Length: 0\r\n\r\n".getBytes();
    ParseResult result = HttpRequestParser.parse(input);
    assertEquals("example.com", result.getHost());
    String rewritten = new String(result.getRewritten());
    assertTrue(rewritten.startsWith("POST /api/data HTTP/1.1\r\n"));
}

// TC-6: Proxy-Connection 头被移除
@Test
void testProxyConnectionRemoved() {
    byte[] input = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\nProxy-Connection: keep-alive\r\nAccept: */*\r\n\r\n".getBytes();
    ParseResult result = HttpRequestParser.parse(input);
    String rewritten = new String(result.getRewritten());
    assertFalse(rewritten.contains("Proxy-Connection"));
    assertTrue(rewritten.contains("Accept: */*"));
}
```

#### Fallback 场景

```java
// TC-7: 相对 URL + Host 头 fallback
@Test
void testRelativeUrlWithHostHeader() {
    byte[] input = "GET /page HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes();
    ParseResult result = HttpRequestParser.parse(input);
    assertEquals("example.com", result.getHost());
    assertEquals(80, result.getPort());
}

// TC-8: 相对 URL + Host 头带端口
@Test
void testRelativeUrlWithHostPort() {
    byte[] input = "GET /page HTTP/1.1\r\nHost: example.com:9090\r\n\r\n".getBytes();
    ParseResult result = HttpRequestParser.parse(input);
    assertEquals("example.com", result.getHost());
    assertEquals(9090, result.getPort());
}
```

#### 异常场景

```java
// TC-9: null 输入
@Test
void testNullInput() {
    assertThrows(IllegalArgumentException.class, () -> HttpRequestParser.parse(null));
}

// TC-10: 空数组
@Test
void testEmptyInput() {
    assertThrows(IllegalArgumentException.class, () -> HttpRequestParser.parse(new byte[0]));
}

// TC-11: 相对 URL 且无 Host 头
@Test
void testRelativeUrlNoHost() {
    byte[] input = "GET /page HTTP/1.1\r\nAccept: */*\r\n\r\n".getBytes();
    assertThrows(IllegalArgumentException.class, () -> HttpRequestParser.parse(input));
}

// TC-12: 请求行格式错误（不足 3 段）
@Test
void testMalformedRequestLine() {
    byte[] input = "INVALID\r\n\r\n".getBytes();
    assertThrows(IllegalArgumentException.class, () -> HttpRequestParser.parse(input));
}
```

### 5.3 手工验证方法

如果项目未配置单元测试框架，可以写一个临时的 `main` 方法：

```java
public static void main(String[] args) {
    String raw = "GET http://httpbin.org/get?name=test HTTP/1.1\r\n"
               + "Host: httpbin.org\r\n"
               + "Proxy-Connection: keep-alive\r\n"
               + "Accept: */*\r\n"
               + "\r\n";
    ParseResult result = HttpRequestParser.parse(raw.getBytes());
    System.out.println("Host: " + result.getHost());
    System.out.println("Port: " + result.getPort());
    System.out.println("Rewritten:\n" + new String(result.getRewritten()));
}
```

预期输出：

```
Host: httpbin.org
Port: 80
Rewritten:
GET /get?name=test HTTP/1.1
Host: httpbin.org
Accept: */*

```

注意 `Proxy-Connection` 行消失，URL 从绝对变成了相对。

## 6. 完成标准

- [ ] `HttpRequestParser.java` 编写完成，放在 `proxy-local/.../handler/` 包下
- [ ] `mvn compile -pl proxy-local` 编译通过，无错误无警告
- [ ] 上述 12 个测试用例全部通过（或 main 方法手工验证通过）
- [ ] 代码包含完整的 Javadoc 注释

## 7. 不做什么

本任务不涉及以下内容，由后续任务完成：

- 不修改 `HttpConnectHandler`（Task-3 负责）
- 不创建 `HttpProxyRelayHandler`（Task-2 负责）
- 不涉及 Netty pipeline 操作
- 不涉及网络请求或远程代理建连
- 不处理 HTTP body（body 部分由后续 handler 透传）
