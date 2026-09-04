---

### `runtime/component/agi-ai/mcp/instructions/facets/HipaaCompliance.md`

```markdown
# Facet Overlay: HIPAA & Healthcare Compliance

This overlay is active because `hipaa="true"` or the domain context is clinical.

## Mandatory Compliance Rules
1. **Protected Health Information (PHI) Encryption**:
   - Any entity field storing PHI, Personally Identifiable Information (PII), social security numbers, medical record identifiers, clinical diagnosis text, or dates of birth MUST have `encrypt="true"`.
   - Example:
     ```xml
     <field name="medicalRecordNum" type="text-medium" encrypt="true"/>
     <field name="socialSecurityNum" type="text-short" encrypt="true"/>
     ```
2. **Audit Logging Enforcement**:
   - Any entity storing medical records, patient encounters, prescriptions, allergies, or clinical observations MUST declare `enable-audit-log="true"`.
   - Example:
     ```xml
     <entity entity-name="PatientEncounter" package="nursinghome.patient" enable-audit-log="true">
     ```
3. **Screen Masking**:
   - Do not display full unmasked identifiers in general overview lists. Use masked formatting where appropriate.