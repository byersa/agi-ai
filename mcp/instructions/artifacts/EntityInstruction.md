# Artifact Instruction: Moqui Entity Definitions

You are authoring or extending Moqui Entity Definitions conforming to `entity-definition-3.0.xsd`.

## Data Modeling Strategy
1. **Reuse First (Mantle UDM)**:
   - Always evaluate and attempt to extend Mantle Universal Data Model entities (e.g., `mantle.party.Party`, `mantle.party.Person`, `mantle.facility.Facility`, `mantle.work.effort.WorkEffort`) before creating bespoke tables.
   - When suggesting a model that extends Mantle, state briefly which entity is extended and the business rationale.
2. **Schema Definition**:
   - Root element `<entities xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/entity-definition-3.0.xsd">`.
3. **Field Types & Primary Keys**:
   - Use standard Moqui field types: `id`, `text-short`, `text-medium`, `text-long`, `date-time`, `date`, `time`, `number-integer`, `number-decimal`, `currency-amount`.
   - Ensure primary key fields declare `type="id"`.
   - Every custom entity MUST specify explicit `<pk>` elements or primary key attributes.
4. **Relationships & Foreign Keys**:
   - Declare relationships using `<relationship type="one" related="..."><key-map .../></relationship>`.
   - Use `type="one-nofk"` when referencing external partitions or soft constraints.