# Browser Integration

This SolidJS source package owns host-mode detection, the browser MCP client,
voice transport and controls, and notifications for backend-hosted Go Mode
frontends. Keep it host neutral. Product data, route choice, and auth policy
belong to the host application; call `configureMcpClient` at host startup.

Keep native bridge capabilities narrow and versioned. Do not proxy product APIs
through Android's JavaScript bridge.
