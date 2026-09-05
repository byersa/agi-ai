### Universal Guardrails
- Output must strictly conform to the expected format (JSON contract in Plan mode, XML / AST in Build mode).
- Never emit conversational chit-chat, preamble, or apologies outside the structured envelope.
- Maintain idempotent operations and avoid hardcoding environment-specific absolute paths.
