// Checks complete shared catalogs and localized accessible labels without loading the voice runtime.

import assert from "node:assert/strict";
import { test } from "node:test";
import { defaultVoiceMessages } from "./VoiceMessages";
import { voiceMessages as de } from "./voiceMessages/de";
import { voiceMessages as es } from "./voiceMessages/es";
import { voiceMessages as fr } from "./voiceMessages/fr";

for (const [locale, messages] of Object.entries({ en: defaultVoiceMessages, de, es, fr })) {
  test(`${locale} supplies every shared voice label`, () => {
    assert.deepEqual(Object.keys(messages).sort(), Object.keys(defaultVoiceMessages).sort());
    for (const [key, value] of Object.entries(messages)) {
      assert.ok(value.trim().length > 0, `${locale}.${key} is empty`);
    }
  });
}
