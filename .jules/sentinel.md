## 2026-01-04 - JSON Injection via Manual String Concatenation
**Vulnerability:** The `GeminiAIService` was constructing complex JSON payloads using `String.format` and manual string escaping. This is prone to injection vulnerabilities (e.g., if a user input contains characters that break the manual escaping) and produces fragile code that is hard to maintain.
**Learning:** Manual JSON construction should always be avoided in favor of established libraries like Jackson or Gson. These libraries handle escaping and formatting correctly and securely.
**Prevention:** Use `ObjectMapper` (Jackson) to build JSON structures using `ObjectNode` and `ArrayNode`, or serialize POJOs directly.
