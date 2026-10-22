# Browser Integration

This SolidJS source package owns host-mode detection, the browser MCP client,
voice transport and controls, and notifications for backend-hosted Go Mode
frontends. Keep it host neutral. Product data, route choice, and auth policy
belong to the host application; call `configureMcpClient` at host startup.

CSS Module declarations (`*.module.css.d.ts`) ship with the source package.
`make fix` regenerates them; `make verify` and build check freshness. Run
`node scripts/css_module_types.mjs --source-path web/src --watch` from the
repository root while editing CSS for a linked host.
Do not edit generated declarations by hand.

Keep native bridge capabilities narrow and versioned. Do not proxy product APIs
through Android's JavaScript bridge.
