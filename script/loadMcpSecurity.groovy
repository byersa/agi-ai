package org.moqui.ai

import org.moqui.impl.entity.EntityDataLoaderImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

Logger log = LoggerFactory.getLogger("org.moqui.ai.loadMcpSecurity")
def ec = context.ec

// 1. Idempotency Guard: Probe the database to see if the user is already initialized
try {
    var existingUser = ec.entity.find("moqui.security.UserAccount")
            .condition("userId", "SystemSupport")
            .one()
            
    if (existingUser != null && existingUser.currentPassword?.startsWith("\$") == false) {
        log.info("🛡️ AGI Bootstrapper: SystemSupport account found, but password hash is invalid. Forcing repair...")
    } else if (existingUser != null) {
        log.info("✅ AGI Bootstrapper: SystemSupport account is already ruggedly seeded. Skipping load pass.")
        return
    }
} catch (Exception probeError) {
    log.warn("⚠️ AGI Bootstrapper: Database table probe failed (tables might not be initialized yet): ${probeError.message}")
}

// 2. Perform the surgical data insert if the account is missing or broken
String filePath = "runtime/component/agi-ai/data/AgiMcpSecurityData.xml"
File xmlFile = new File(filePath)

if (!xmlFile.exists()) {
    log.error("❌ AGI Bootstrapper: Cannot find target security file at: ${filePath}")
    return
}

log.info("⏳ AGI Bootstrapper: Launching isolated, high-speed security load pass...")

try {
    EntityDataLoaderImpl loader = (EntityDataLoaderImpl) ec.entity.makeDataLoader()
    loader.xmlText(xmlFile.text)
    loader.dataTypes(new HashSet(["setup"])) // Forces the "setup" hash/overwrite pass
    
    long recordsLoaded = loader.load()
    log.info("🎯 AGI Bootstrapper: Success! Seeding complete. Loaded ${recordsLoaded} records cleanly.")
} catch (Exception e) {
    log.error("💥 AGI Bootstrapper: Failed to execute automated load pass: ${e.message}", e)
}