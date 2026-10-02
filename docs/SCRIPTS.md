# Scripts, memory and MCP

Library scripts are manually reviewed finite JSON programs. They do not call a model and cannot execute general Lua, JavaScript, shell or native code. The bounded language is intentionally small enough to audit. Each step calls the same policy gateway as a model action, including additional observations used for label resolution.

Paste this in Library's script editor and choose Review & enable. Install the practice app and grant it Read/Act first.

```json
{
  "id": "practice-note",
  "name": "Type a practice note",
  "parameters": [{"name": "note", "kind": "text", "maxLength": 100}],
  "steps": [
    {"action": {"op": "OPEN", "app": "dev.magicphone.fixture"}},
    {
      "action": {"op": "TEXT", "app": "dev.magicphone.fixture"},
      "findLabel": "Ordinary text · Κείμενο",
      "textParameter": "note"
    },
    {"action": {"op": "OBSERVE", "app": "dev.magicphone.fixture"}}
  ],
  "enabled": false
}
```

Enter parameters as `{"note":"Καλημέρα"}` before Run manually. The editor's local review enables a script; an imported `enabled=true` is ignored. The script runner obtains a fresh permitted observation, requires a unique exact label and binds its node to that snapshot. Do not use scripts for passwords, OTPs or payment authorization.

Parameters support text and integer kinds with maximum lengths. Limits are 40 steps, 12 parameters, 64 KB encoded source and a 120-second wall deadline, plus shared run budgets. No loops, catching, imports, dynamic loading or arbitrary network operations exist. Waiting is bounded. Unknown fields and operations are rejected. A denied step stops the script and does not skip ahead.

Memory and playbooks use the same editable app-scoped note record. Only reviewed notes matching the app being observed enter model context. The model may propose a note but cannot mark it reviewed. Notes are untrusted task context and never grant device/server rights. They can be edited or deleted in Library.

MCP supports the Streamable HTTP 2025-11-25 client request/response subset. Configure a destination and token in Settings, inspect its catalog, read the description/schema and consent to each tool. Tool calls show the server destination and arguments in the local approval. A changed capability fingerprint fails the call; inspect and review it again. Servers are disabled by default. There is no stdio process launch, local shell, server-initiated sampling or automatic reconnection/replay of tool actions. A timed-out call may already have run remotely; inspect before repeating it.

The JSON Schema validator deliberately supports bounded objects, arrays, strings, integers, numbers, booleans, null, enums and selected size/required/additional-property constraints. It rejects references/unknown type forms. Server-side validation still applies. Complex union/composition schemas require a future audited extension; they must not be interpreted as additional authority.
