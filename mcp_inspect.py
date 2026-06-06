"""Real MCP client connecting to mcp-server-jvm-build-tools"""
import asyncio, json, sys, textwrap
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

async def main():
    server_params = StdioServerParameters(
        command="java",
        args=["-jar", "target/mcp-server-jvm-build-tools.jar"]
    )
    
    async with stdio_client(server_params) as (read, write):
        async with ClientSession(read, write) as session:
            await session.initialize()
            
            tools_result = await session.list_tools()
            tools = tools_result.tools
            tool_names = [t.name for t in tools]
            
            print("╔══════════════════════════════════════════════════════════╗")
            print("║  MCP Inspector — mcp-server-jvm-build-tools             ║")
            print("╚══════════════════════════════════════════════════════════╝")
            print()
            print(f"Connected. {len(tools)} tools available:")
            for t in tools:
                desc = (t.description or "")[:60]
                print(f"  • {t.name} — {desc}")
            print()
            
            # 1. Detect build tool
            print("━━━ detect_build_tool ━━━")
            result = await session.call_tool("detect_build_tool", {"projectDir": "/workspace/repo"})
            data = json.loads(json.loads(result.content[0].text))
            for det in data["detections"]:
                if det["detected"]:
                    print(f"  ✓ {det['tool']} — {', '.join(det['matchedFiles'])}")
                    for h in det.get("hints", []):
                        print(f"    {h}")
            print()
            
            # 2. Validate config
            print("━━━ validate_build_configuration ━━━")
            result = await session.call_tool("validate_build_configuration", {"projectDir": "/workspace/repo"})
            data = json.loads(json.loads(result.content[0].text))
            print(f"  Valid: {data['valid']}")
            for i in data.get("issues", []):
                print(f"  ⚠ {i['severity']}: {i['message']}")
            print()
            
            # 3. Check dependency
            print("━━━ check_dependency_version ━━━")
            result = await session.call_tool("check_dependency_version", {
                "groupId": "org.springframework.boot",
                "artifactId": "spring-boot-starter-web",
                "projectDir": "/workspace/repo"
            })
            data = json.loads(json.loads(result.content[0].text))
            print(f"  Latest: {data['latestVersion']}")
            syn = data.get("dependencySyntax", {}).get("maven", "")
            for line in syn.split("\\n")[:4]:
                if line.strip():
                    print(f"  {line}")
            print()
            
            print("╔══════════════════════════════════════════════════════════╗")
            print("║  Real MCP client. Real tool calls. Real responses.      ║")
            print("╚══════════════════════════════════════════════════════════╝")

asyncio.run(main())
