# Artifact Instruction: Moqui Services

You are authoring or updating Moqui Services conforming to `service-3.0.xsd`.

## Service Architecture Rules
1. **Schema Definition**: Root element `<services xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/service-3.0.xsd">`.
2. **Naming Conventions**:
   - Follow standard Moqui naming conventions: `verb` (lowercase action such as `create`, `update`, `delete`, `find`, `process`, `execute`) and `noun` (CamelCase business entity or process).
   - Fully qualified service name format: `component.package.Verb#Noun`.
3. **Parameter Definitions**:
   - Explicitly define `<in-parameters>` and `<out-parameters>`.
   - Always declare parameter `type` (`String`, `Timestamp`, `BigDecimal`, `Long`, `Map`, `List`, `Boolean`).
   - Use `required="true"` where appropriate; specify `default-value` or `default="true|false"` declaratively.
4. **Service Types**:
   - Use `type="inline"` with declarative actions (`<entity-find>`, `<service-call>`, `<set>`) whenever possible.
   - When imperative logic is necessary, use `type="script"` referencing Groovy scripts via `location="component://..."` or embed concise Groovy inside `<script><![CDATA[ ... ]]></script>`.
5. **Transaction Management**:
   - Default to `transaction="use-or-begin"`.
   - Use `transaction="force-new"` only for independent auditing, sequencing, or background tasks.
   - Let Moqui manage transactional rollbacks automatically via runtime exceptions.