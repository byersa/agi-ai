package org.moqui.ai.tools

import org.moqui.context.ExecutionContext
import org.moqui.llm.LlmTool

class WriteScreenXmlTool implements LlmTool {
    public static final String NAME = "write_screen_xml"

    @Override String getName() { return NAME }
    @Override String getDescription() {
        return "Write or update a canonical Moqui screen XML file on disk. " +
               "Pass target artifactUri and the complete valid screen XML string."
    }

    @Override
    Map<String, Object> getParametersSchema() {
        return [
            type: "object",
            required: ["artifactUri", "xml"],
            properties: [
                artifactUri: [
                    type: "string",
                    description: "Target location, e.g. component://nursinghome/screen/nursinghome.xml"
                ],
                xml: [
                    type: "string",
                    description: "Complete canonical Moqui XML starting with <?xml or <screen>"
                ],
                instruction: [
                    type: "string",
                    description: "Summary of changes made to the screen artifact."
                ]
            ]
        ]
    }

    @Override LlmTool.Execution getExecution() { return LlmTool.Execution.SERVER }

    @Override
    Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        String uri = arguments.artifactUri?.toString()
        String rawXml = arguments.xml?.toString()

        if (!uri || !rawXml) {
            return groovy.json.JsonOutput.toJson([
                status : "ERROR",
                error  : "Both artifactUri and xml are required."
            ])
        }

        try {
            Map result = ec.service.sync().name("org.moqui.ide.AgiIdeServices.save#ScreenXml").parameters([
                artifactUri    : uri,
                xmlText        : rawXml,
                verifyEntities : false
            ]).call()

            return groovy.json.JsonOutput.toJson([
                status       : "SUCCESS",
                artifactUri  : uri,
                savedFilePath: result.savedFilePath?.toString() ?: "",
                message      : "Successfully validated and saved ${uri} to disk."
            ])
        } catch (Exception ex) {
            return groovy.json.JsonOutput.toJson([
                status : "ERROR",
                error  : "Failed to save screen XML: ${ex.message}",
                details: ex.toString()
            ])
        }
    }
}