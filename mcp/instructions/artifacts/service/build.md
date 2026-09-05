### Mandatory Protocol for Service Build Mode:
- Declare service XML under `<services>` conforming to `moqui-service-3.xsd`.
- Define `<in-parameters>` with clear type definitions and required flags.
- For business logic, favor declarative entity operations (`<entity-find>`, `<entity-make-value>`, `<entity-create>`) before falling back to `<script>`.
