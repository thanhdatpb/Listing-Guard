package com.thanhdatpb.listingguard.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.mcp.ApiStub.Reply;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The protocol, end to end, through the SDK's own client: over Streamable HTTP in this process, and over stdio against a
 * child process started from the module's classes (the real {@code main}). The API is a stub; nothing needs Docker.
 */
@Timeout(90)
class McpTransportsTest {

    private static final ObjectMapper M = ToolResults.newMapper();
    private static final List<String> TOOLS = List.of("check_listing", "check_trademark", "check_variations",
            "screen_personalization", "get_limits", "get_usage");

    private static McpServerConfig httpConfig(ApiStub stub) {
        return new McpServerConfig(ApiStub.KEY, stub.baseUri(), Duration.ofSeconds(5), TransportKind.HTTP,
                InetAddress.getLoopbackAddress(), 0, false);
    }

    private static McpSyncClient httpClient(int port) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).endpoint(HttpHost.ENDPOINT).build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30)).build();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    private static void assertToolList(McpSyncClient client, InitializeResult init) {
        List<Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrderElementsOf(TOOLS);
        for (Tool tool : tools) {
            assertThat(tool.description()).as(tool.name()).isNotBlank();
            assertThat(tool.inputSchema()).as(tool.name()).containsEntry("type", "object").containsKey("properties");
            assertThat(tool.annotations()).as(tool.name()).isNotNull();
            boolean reader = tool.name().equals("get_limits") || tool.name().equals("get_usage");
            assertThat(tool.annotations().readOnlyHint()).as(tool.name()).isEqualTo(reader);
            assertThat(tool.annotations().destructiveHint()).as(tool.name()).isFalse();
            assertThat(tool.annotations().openWorldHint()).as(tool.name()).isFalse();
        }
        assertThat(init.serverInfo().name()).isEqualTo("listing-guard-mcp");
        assertThat(init.instructions()).contains("do not decide", "INCOMPLETE", "UNKNOWN")
                .doesNotContain(ApiStub.KEY);
        assertThat(init.capabilities().tools()).isNotNull();
        assertThat(init.capabilities().resources()).isNull();
        assertThat(init.capabilities().prompts()).isNull();
    }

    @Test
    void overStreamableHttpTheClientListsTheSixToolsAndCallsThem() throws Exception {
        try (ApiStub stub = new ApiStub();
                RunningServer server = Launcher.start(httpConfig(stub), InputStream.nullInputStream(),
                        OutputStream.nullOutputStream());
                McpSyncClient client = httpClient(server.httpPort())) {
            InitializeResult init = client.initialize();
            assertToolList(client, init);

            String usage = "{\"enforced\":true,\"plan\":\"SELLER\",\"monthlyQuota\":1000,\"used\":42}";
            stub.replying(Reply.json(200, usage));
            CallToolResult result = client.callTool(new CallToolRequest("get_usage", Map.of()));
            assertThat(result.isError()).isFalse();
            assertThat(text(result)).isEqualTo(usage);
            JsonNode structured = M.valueToTree(result.structuredContent());
            assertThat(structured).isEqualTo(M.readTree(usage));
            assertThat(stub.only().method()).isEqualTo("GET");
            assertThat(stub.only().path()).isEqualTo("/api/v1/usage");
            assertThat(stub.only().header("X-API-Key")).isEqualTo(ApiStub.KEY);

            stub.requests().clear();
            stub.replying(Reply.problem(503, "{\"code\":\"trademark-not-configured\",\"status\":503,\"requestId\":\"r\"}"));
            CallToolResult notConfigured = client.callTool(new CallToolRequest("check_trademark",
                    Map.of("marketplace", "ETSY", "listing", Map.of("title", "x"))));
            assertThat(notConfigured.isError()).isFalse();
            JsonNode s = M.valueToTree(notConfigured.structuredContent());
            assertThat(s.get("riskLevel").asText()).isEqualTo("UNKNOWN");
            assertThat(s.has("findings")).isFalse();

            stub.requests().clear();
            stub.replying(Reply.problem(429, "{\"code\":\"quota-exceeded\",\"status\":429,\"requestId\":\"q\"}"));
            CallToolResult refused = client.callTool(new CallToolRequest("check_listing",
                    Map.of("marketplace", "ETSY", "listing", Map.of("title", "x"))));
            assertThat(refused.isError()).isTrue();
            assertThat(M.valueToTree(refused.structuredContent()).get("code").asText()).isEqualTo("quota-exceeded");

            CallToolResult bad = client.callTool(new CallToolRequest("check_listing", Map.of("marketplace", "ETSY")));
            assertThat(bad.isError()).isTrue();
            assertThat(M.valueToTree(bad.structuredContent()).get("code").asText()).isEqualTo("invalid-arguments");
        }
    }

    @Test
    void aRequestThatNamesAnotherOriginIsRefused() throws Exception {
        try (ApiStub stub = new ApiStub();
                RunningServer server = Launcher.start(httpConfig(stub), InputStream.nullInputStream(),
                        OutputStream.nullOutputStream())) {
            HttpClient http = HttpClient.newHttpClient();
            String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                    + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.httpPort() + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(initialize));

            HttpResponse<String> foreign = http.send(request.copy().header("Origin", "http://evil.example").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(foreign.statusCode()).isEqualTo(403);

            HttpResponse<String> local = http.send(request.copy().header("Origin", "http://localhost:3000").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(local.statusCode()).isEqualTo(200);

            HttpResponse<String> noOrigin = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(noOrigin.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void aRequestWithAForeignHostHeaderIsRefusedAgainstDnsRebinding() throws Exception {
        try (ApiStub stub = new ApiStub();
                RunningServer server = Launcher.start(httpConfig(stub), InputStream.nullInputStream(),
                        OutputStream.nullOutputStream());
                Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.httpPort())) {
            socket.setSoTimeout(10_000);
            String body = "{}";
            String request = "POST /mcp HTTP/1.1\r\nHost: evil.example:" + server.httpPort() + "\r\n"
                    + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                    + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            String statusLine = new String(socket.getInputStream().readNBytes(32), StandardCharsets.US_ASCII);

            assertThat(statusLine).startsWith("HTTP/1.1 421");
        }
    }

    @Test
    void overStdioTheRealMainListsTheSixToolsAndCallsOne() throws Exception {
        try (ApiStub stub = new ApiStub()) {
            String usage = "{\"enforced\":false,\"plan\":\"FREE\",\"used\":0}";
            stub.replying(Reply.json(200, usage));
            List<String> stderr = new CopyOnWriteArrayList<>();
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ServerParameters params = ServerParameters.builder(java)
                    .args("-cp", System.getProperty("java.class.path"), McpServerApplication.class.getName())
                    .env(Map.of(McpServerConfig.API_KEY, ApiStub.KEY, McpServerConfig.BASE_URL, stub.baseUri().toString()))
                    .build();
            StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
            transport.setStdErrorHandler(stderr::add);

            try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
                    .initializationTimeout(Duration.ofSeconds(30)).build()) {
                InitializeResult init = client.initialize();
                assertToolList(client, init);

                CallToolResult result = client.callTool(new CallToolRequest("get_usage", Map.of()));
                assertThat(result.isError()).isFalse();
                assertThat(text(result)).isEqualTo(usage);
                assertThat(stub.only().header("X-API-Key")).isEqualTo(ApiStub.KEY);
            }

            // The logs are on standard error and never carry the key.
            assertThat(String.join("\n", stderr)).doesNotContain(ApiStub.KEY).doesNotContain("SECRETKEY");
            assertThat(String.join("\n", stderr)).contains("transport=stdio");
        }
    }

    @Test
    void theProcessDoesNotStartWithoutTheKeyAndSaysWhichSettingIsMissing() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                McpServerApplication.class.getName());
        builder.environment().remove(McpServerConfig.API_KEY);
        builder.environment().put(McpServerConfig.BASE_URL, "http://127.0.0.1:1");
        Process process = builder.start();
        process.getOutputStream().close();

        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isEqualTo(Launcher.EXIT_CONFIGURATION);
        assertThat(out).as("standard output carries only protocol messages").isEmpty();
        assertThat(err).contains(McpServerConfig.API_KEY);
    }
}
