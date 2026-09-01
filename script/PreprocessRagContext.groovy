package org.moqui.ide

import org.moqui.impl.entity.EntityDefinition

contextItems = []
String cleanPrompt = (prompt ?: "").toLowerCase()
String targetUri = artifactUri ?: ""

// =====================================================================================
// 1. SIBLING SCREEN ARTIFACT DISCOVERY (Same Directory Resolution)
// =====================================================================================
if (targetUri && targetUri.startsWith("component://") && targetUri.endsWith(".xml")) {
    try {
        def targetRef = ec.resource.getLocationReference(targetUri)
        def parentRef = targetRef?.getParent()

        if (parentRef && parentRef.exists) {
            def entries = parentRef.directoryEntries
            for (def entry in entries) {
                String entryLoc = entry.location
                // Look for sibling screens that aren't the target screen itself
                if (entryLoc.endsWith(".xml") && entryLoc != targetUri) {
                    String siblingName = entryLoc.substring(entryLoc.lastIndexOf('/') + 1)

                    // Fetch existing buffer AST or fallback to disk text
                    Map bufRes = ec.service.sync().name("org.moqui.ide.AgiWorkspaceServices.get#WorkspaceBuffer")
                        .parameters([artifactUri: entryLoc])
                        .call()

                    String schemaSnippet = ""
                    if (bufRes?.metaJsonBuffer) {
                        schemaSnippet = bufRes.metaJsonBuffer
                    } else if (entry.exists) {
                        schemaSnippet = entry.getText()?.take(500) + "..."
                    }

                    contextItems.add([
                        category: 'SIBLING_ARTIFACT',
                        title   : "${siblingName} Screen Schema",
                        type    : 'sibling-screen',
                        snippet : schemaSnippet,
                        enabled : true,
                        uri     : entryLoc
                    ])
                }
            }
        }
    } catch (Exception ex) {
        ec.logger.warn("⚠️ Error gathering sibling RAG context for ${targetUri}: ${ex.message}")
    }
}

// =====================================================================================
// 2. COMPLIANCE RULE DETECTION
// =====================================================================================
if (cleanPrompt.contains('patient') || cleanPrompt.contains('health') || cleanPrompt.contains('medical') || cleanPrompt.contains('ssn')) {
    contextItems.add([
        category: 'HIPAA COMPLIANCE',
        title: 'PHI Data Encryption & Audit Logging',
        type: 'rule',
        snippet: 'Any field storing PHI MUST declare encrypt="true". Sensitive domain entities MUST declare enable-audit-log="true".',
        enabled: true
    ])
}

// =====================================================================================
// 3. UDM & DOMAIN ENTITY MATCHING
// =====================================================================================
Set<String> promptTokens = cleanPrompt.replaceAll(/[^a-zA-Z0-9\s]/, ' ')
    .split(/\s+/)
    .findAll { it.length() > 2 } as Set<String>

List allEntityNames = new ArrayList(ec.entity.getAllEntityNames())

String targetComp = targetComponent ?: 'nursinghome'
List candidateEntities = allEntityNames.findAll { name ->
    String sName = name.toString()
    sName.startsWith("mantle.") || (targetComp && sName.startsWith(targetComp + "."))
}

Set<String> matchedEntities = new LinkedHashSet<>()

for (Object entObj in candidateEntities) {
    String entityName = entObj.toString()
    String shortName = entityName.substring(entityName.lastIndexOf('.') + 1).toLowerCase()
    String pkgName = entityName.toLowerCase()

    for (String token in promptTokens) {
        if (shortName.contains(token) || token.contains(shortName) || pkgName.contains(token)) {
            matchedEntities.add(entityName)
            break
        }
    }
}

// Default Party anchor
if (candidateEntities.contains("mantle.party.Party")) {
    matchedEntities.add("mantle.party.Party")
}

// Build dynamic RAG tiles (limit to top 10)
for (String entityName in matchedEntities.take(10)) {
    try {
        EntityDefinition ed = ec.entity.getEntityDefinition(entityName)
        if (ed != null) {
            String pks = ed.getPkFieldNames().join(', ')
            String nonPkFields = ed.getFieldNames().findAll { !ed.isPkField(it) }.take(8).join(', ')
            String rels = ed.getRelationshipNames().take(4).join(', ')

            contextItems.add([
                category: 'UDM ENTITY',
                title: entityName,
                type: 'entity',
                entityName: entityName,
                snippet: "PK: [${pks}] | Fields: ${nonPkFields}... | Rels: [${rels}]",
                enabled: true
            ])
        }
    } catch (Exception e) {
        // Skip unresolvable entity names
    }
}

// =====================================================================================
// 4. DECLARATIVE SCREEN STANDARDS
// =====================================================================================
contextItems.add([
    category: 'MOQUI MACRO',
    title: 'Moqui Declarative Screen Standards (xml-screen-3.xsd)',
    type: 'convention',
    snippet: 'Use declarative forms (<form-single>, <form-list>) and subscreens navigation (<subscreens-tabs>, <subscreens-active>). Avoid custom Vue script implementations where declarative XML suffices.',
    enabled: true
])

context.contextItems = contextItems