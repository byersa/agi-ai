# Artifact Instruction: Moqui Automated Test Suites

You are authoring Moqui XML Test Suites and Assertion Manifests conforming to `xml-actions-3.0.xsd`.

## Test Lifecycle Rules
1. **Declarative Test Syntax**:
   - Use standard Moqui test scripts or XML action fixtures located under `component://<component>/test/`.
2. **Structure & Assertions**:
   - Set up test state cleanly within `<actions>`.
   - Execute target services via `<service-call>`.
   - Assert results declaratively:
     - `<assert condition="fieldA == 'expected'"/>`
     - `<assert condition="ec.entity.find('my.Entity').condition('id', id).one() != null"/>`
3. **Pipeline Manifest JSON**:
   - When generating or updating JSON test manifests for the AGI studio pipeline, adhere to the `TestPayload.schema.json` structure:
   ```json
   [
     {
       "stepId": "01_CALL_SERVICE",
       "title": "Execute Business Service",
       "action": "SERVICE_CALL",
       "serviceName": "nursinghome.patient.PatientServices.create#Patient",
       "parameters": { ... },
       "assertions": {
         "expectStatus": "SUCCESS"
       }
     },
     {
       "stepId": "02_ASSERT_DB",
       "title": "Verify Database Persistence",
       "action": "ASSERT_STATE",
       "assertions": {
         "expectFileExists": false,
         "expectedValues": { "statusId": "PasActive" }
       }
     }
   ]