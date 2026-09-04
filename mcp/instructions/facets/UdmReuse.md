# Facet Overlay: Mantle UDM Reuse & Extension

This overlay is active because the active domain is operational/clinical or references Mantle entities.

## Reuse Directives
1. **Party Hierarchy**:
   - Do not invent separate user or patient root tables. Patients, staff, physicians, and emergency contacts are parties (`mantle.party.Party`) with person records (`mantle.party.Person`).
   - Define roles using `mantle.party.PartyRole` (e.g., `Patient`, `Physician`, `Nurse`, `Administrator`).
2. **Facility & Location**:
   - Rooms, beds, wings, and nursing facilities map to `mantle.facility.Facility` with parent-child relationships (`parentFacilityId`).
3. **Activities & Encounters**:
   - Appointments, care shifts, patient intakes, and clinical treatments map to or extend `mantle.work.effort.WorkEffort`.