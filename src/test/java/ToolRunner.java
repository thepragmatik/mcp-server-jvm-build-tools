import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.*;

public class ToolRunner {
    public static void main(String[] args) throws Exception {
        String tool = args[0];
        Map<String, Object> params = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            params.put(kv[0], kv[1]);
        }
        var transport = new StdioClientTransport(
            ServerParameters.builder("java")
                .args("-jar", "target/mcp-server-jvm-build-tools.jar")
                .build());
        var client = McpClient.sync(transport).build();
        client.initialize();
        var result = client.callTool(new McpSchema.CallToolRequest(tool, params));
        for (var c : result.content()) {
            if (c instanceof McpSchema.TextContent) {
                System.out.println(((McpSchema.TextContent) c).text());
            }
        }
        client.closeGracefully();
    }
}
