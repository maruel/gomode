// Linting rules for Go Mode browser TypeScript and Solid components.

import eslint from "@eslint/js";
import tseslint from "typescript-eslint";
import solid from "eslint-plugin-solid/configs/typescript";
import globals from "globals";
import prettier from "eslint-config-prettier";

export default tseslint.config(
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: [
      "e2e/voice-preview/*.tsx",
      "frontend/src/**/*.{ts,tsx}",
      "web/src/**/*.{ts,tsx}",
      "web/tests/**/*.{ts,tsx}",
    ],
    ...solid,
    languageOptions: {
      globals: { ...globals.browser },
      parserOptions: { project: "./frontend/tsconfig.json" },
    },
    rules: {
      "no-unused-vars": "off",
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
      "@typescript-eslint/no-explicit-any": "error",
      "@typescript-eslint/consistent-type-imports": ["error", { prefer: "type-imports" }],
      "no-shadow": "off",
      "no-debugger": "error",
      "no-console": ["error", { allow: ["warn", "error"] }],
      eqeqeq: ["error", "always", { null: "ignore" }],
      "no-var": "error",
      "prefer-const": "error",
      "prefer-arrow-callback": "error",
      "object-shorthand": "error",
      "solid/imports": "error",
      "solid/jsx-no-duplicate-props": "error",
      "solid/jsx-no-undef": ["error", { typescriptEnabled: true }],
      "solid/jsx-uses-vars": "error",
      "solid/no-destructure": "error",
      "solid/reactivity": "error",
    },
  },
  {
    files: ["e2e/voice-preview/*.tsx", "frontend/src/**/*.{ts,tsx}", "web/src/**/*.{ts,tsx}"],
    ignores: ["**/*.test.{ts,tsx}"],
    rules: {
      "@typescript-eslint/no-non-null-assertion": "error",
      "@typescript-eslint/no-floating-promises": "error",
      "@typescript-eslint/no-for-in-array": "error",
      "@typescript-eslint/no-array-delete": "error",
      "@typescript-eslint/prefer-promise-reject-errors": "error",
      "@typescript-eslint/no-misused-spread": "error",
      "@typescript-eslint/await-thenable": "error",
      "@typescript-eslint/no-base-to-string": "error",
      "@typescript-eslint/restrict-template-expressions": ["error", { allowNumber: true }],
      "@typescript-eslint/only-throw-error": "error",
      "@typescript-eslint/require-await": "error",
      "@typescript-eslint/no-misused-promises": ["error", { checksVoidReturn: { attributes: false } }],
      "@typescript-eslint/no-unnecessary-type-assertion": "error",
      "@typescript-eslint/no-deprecated": "error",
      "@typescript-eslint/no-unnecessary-condition": "error",
      "@typescript-eslint/no-unsafe-argument": "error",
      "@typescript-eslint/no-unsafe-assignment": "error",
      "@typescript-eslint/no-unsafe-call": "error",
      "@typescript-eslint/no-unsafe-member-access": "error",
      "@typescript-eslint/no-unsafe-return": "error",
      "@typescript-eslint/switch-exhaustiveness-check": ["error", { considerDefaultExhaustiveForUnions: true }],
      "@typescript-eslint/use-unknown-in-catch-callback-variable": "error",
    },
  },
  {
    // Browser capability types are optimistic and async state can change across awaits.
    files: [
      "web/src/BrowserVoiceShell.tsx",
      "web/src/DeviceLogin.tsx",
      "web/src/FrontendVoiceContext.ts",
      "web/src/ServiceNotifications.ts",
      "web/src/VoiceOverlay.tsx",
      "web/src/VoiceSession.ts",
      "web/src/VoiceSettings.tsx",
    ],
    rules: { "@typescript-eslint/no-unnecessary-condition": "off" },
  },
  {
    files: ["**/*.test.{ts,tsx}"],
    // Test fixtures assert the existence of their mock calls and DOM elements.
    rules: { "@typescript-eslint/no-non-null-assertion": "off" },
  },
  {
    files: ["**/*.tsx"],
    // Solid's ref={element} assigns the variable during rendering.
    rules: { "no-unassigned-vars": "off" },
  },
  { files: ["web/tests/**/*.{ts,tsx}"], languageOptions: { globals: { ...globals.node } } },
  { files: ["e2e/voice-preview/*.js"], languageOptions: { globals: { ...globals.browser } } },
  { files: ["scripts/*.mjs"], languageOptions: { globals: { ...globals.node } } },
  { ignores: ["**/*.module.css.d.ts", "android/**", "frontend/dist/**", "node_modules/**", "sdk/**"] },
  prettier,
);
