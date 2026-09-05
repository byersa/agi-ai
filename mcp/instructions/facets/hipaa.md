### HIPAA & Healthcare Compliance Invariants:
- PHI Encryption: Any entity field storing Personally Identifiable Information (PII) or Protected Health Information (PHI) such as SSN, MRN, or clinical narratives MUST declare `encrypt="true"`.
- Audit Logging: Any sensitive entity storing patient, encounter, diagnosis, allergy, or prescription data MUST declare `enable-audit-log="true"`.
- Permission Gating: Screens, transitions, and service calls exposing PHI must explicitly require appropriate permissions (e.g., `PATIENT_VIEW`, `CLINICAL_PHI_VIEW`).
- Display Masking: Sensitive identifiers displayed in read-only summary cards or tabular lists must be masked.
