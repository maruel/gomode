// Linting rules for Go Mode browser TypeScript and Solid components.

import eslint from "@eslint/js";
import tseslint from "typescript-eslint";
import solid from "eslint-plugin-solid/configs/typescript";
import jsxA11y from "eslint-plugin-jsx-a11y";
import globals from "globals";
import prettier from "eslint-config-prettier";

export default tseslint.config(
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["web/src/**/*.{ts,tsx}", "web/tests/**/*.{ts,tsx}"],
    ...solid,
    plugins: { ...solid.plugins, "jsx-a11y": jsxA11y },
    languageOptions: {
      globals: { ...globals.browser },
    },
    rules: {
      "no-unused-vars": "off",
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
      "@typescript-eslint/no-explicit-any": "error",
      "@typescript-eslint/consistent-type-imports": ["error", { prefer: "type-imports" }],
      "no-debugger": "error",
      "jsx-a11y/alt-text": "error",
      "jsx-a11y/anchor-has-content": "error",
      "jsx-a11y/aria-props": "error",
      "jsx-a11y/aria-role": "error",
      "jsx-a11y/click-events-have-key-events": "error",
      "jsx-a11y/interactive-supports-focus": "error",
      "jsx-a11y/label-has-associated-control": "error",
      "solid/imports": "error",
      "solid/jsx-no-duplicate-props": "error",
      "solid/jsx-no-undef": ["error", { typescriptEnabled: true }],
      "solid/jsx-uses-vars": "error",
      "solid/no-destructure": "error",
      "solid/reactivity": "error",
    },
  },
  { files: ["web/tests/**/*.{ts,tsx}"], languageOptions: { globals: { ...globals.node } } },
  { files: ["scripts/quiet-test-reporter.mjs"], languageOptions: { globals: { ...globals.node } } },
  { ignores: ["sdk/**", "android/**", "node_modules/**"] },
  prettier,
);
