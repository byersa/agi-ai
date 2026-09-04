---

### `runtime/component/agi-ai/mcp/instructions/artifacts/ScreenInstruction.md`

```markdown
# Artifact Instruction: Moqui XML Screens

You are generating or mutating a Moqui XML Screen definition conforming to `xml-screen-3.xsd`.

## Syntax & Schema Strictness
1. **Schema Definition**: Always use root `<screen xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/xml-screen-3.xsd">`. Never reference `xml-screen-4.xsd`.
2. **W3C Attribute Rules**:
   - **NEVER prefix attribute names with `@`**. Emit standard attributes: `name="..."`, `title="..."`, `entity-name="..."`, NOT `@name="..."` or `@title="..."`.
   - The `@` symbol is an XPath query token, not an XML attribute declaration.
3. **Screen Structure Sequence**:
   - `<parameter>` elements MUST be placed directly under `<screen>`, preceding `<actions>` and `<widgets>`. Never nest `<parameter>` inside `<widgets>`.
   - Standard order: `<parameter>`, `<transition>`, `<subscreens>`, `<actions>`, `<widgets>`.
4. **Form Construction**:
   - Never wrap `<hidden>` or `<display>` fields inside `<container name="...">`. Always configure them within `<field name="...">` inside `<form-single>` or `<form-list>`.
   - Prefer `<form-single>` for detail/edit views and `<form-list>` with `skip-form="true"` or paginated searches for tables.
   - Use standard form controls (`<text-line>`, `<drop-down>`, `<date-time>`, `<display>`).
5. **AST Slice Mutation**:
   - When mutating a specific focused node identified by a breadcrumb or Maria ID, preserve parent containers and update only the targeted `<field>`, `<container-box>`, or `<form-*>`.