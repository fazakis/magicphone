# Direct account capability probes

Tested 2026-10-02 at the owner's explicit request, using the existing selected ChatGPT OAuth account inside MagicPhone on the dedicated Android 15 emulator. This follows the catalog-only observations in VERIFICATION-REFRESH.md. Credentials stayed in the app process; no credential files or raw responses were exported. No API-key account was used.

The production APK, selected model/settings and conversation history were unchanged. Only the opt-in instrumentation APK was rebuilt/installed. Requests used the existing destination-restricted HttpTransport and public `https://api.openai.com/v1/responses`, with `store:false`, streaming, Low reasoning and a tiny instruction to return exactly OK. They deliberately reached the server independently of the model picker's catalog filtering. No phone tools were advertised or executed during these text probes.

| Requested model | Requested tier | HTTP | Completed / exact OK | Returned model | Returned tier | Total ms | First text ms |
|---|---|---|---|---|---|---|---|
| gpt-6-astra | omitted | 200 | yes / yes | gpt-6-astra | default | 5418 | 5132 |
| gpt-6.1-sol | omitted | 200 | yes / yes | gpt-6.1-sol | default | 3111 | 2870 |
| gpt-6-astra | ultrafast | 200 | yes / yes | gpt-6-astra | default | 2636 | 2333 |
| gpt-6.1-sol | ultrafast | 200 | yes / yes | gpt-6.1-sol | default | 3106 | 2829 |
| gpt-6-astra | priority (advertised Fast) | 200 | yes / yes | gpt-6-astra | default | 3590 | 3379 |

## Findings

**GPT-6.1 Sol is usable with this ChatGPT connection despite being absent from its model catalog.** Catalog absence is therefore not an authoritative denial of inference access.

**Ultrafast requests are accepted, but Ultrafast execution is not confirmed.** Both responses explicitly report default processing. The advertised Fast/priority control also reports default. These results do not distinguish parameter ignoring, connection-specific handling or fallback; they establish only the reported processing tier. The faster individual response with an Ultrafast parameter is not proof that Ultrafast ran. These short, single requests are not a comparative performance benchmark.

A further request through the unchanged production `ResponsesProvider` with GPT-6.1 Sol successfully parsed one namespaced `phone.perform` call with operation `COMPLETE`, in **4836 ms**. The tool call was inspected only; zero device actions were executed. An earlier harness assertion incorrectly required the model to reproduce an exact literal completion string; the model used different wording. The final check verifies the typed COMPLETE call and records the wording mismatch separately. This verifies tool-format compatibility, not a full Sol-driven phone task.

## Reproduction and evidence

`LiveModelAccessTest` is emulator-only and skips unless `liveModelAccess=true` is explicitly supplied after account-owner authorization. The default run checks the first four rows. `capabilityFollowup=true` checks the Fast control plus production tool format; add `toolOnly=true` for the latter alone. Test output contains only model/tier IDs, status/operation enums, booleans and timings. Tokens, request/response bodies and account details are never logged.

The first four probes completed in a passing 17.154-second instrumentation run. The final tool-format check passed in 4.862 seconds. Sanitized results and build/test output are retained in ignored `artifacts/model-access-probe/`. The production 0.2.0 APK and public releases were not modified.
