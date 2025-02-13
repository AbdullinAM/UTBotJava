package org.utbot.sarif

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Useful links:
 * - [Official SARIF documentation](https://github.com/microsoft/sarif-tutorials/blob/main/README.md)
 * - [SARIF format documentation by GitHub](https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning)
 */
data class Sarif(
    @JsonProperty("\$schema") val schema: String,
    val version: String,
    val runs: List<SarifRun>
) {
    companion object {

        private val jsonMapper = jacksonObjectMapper()
            .registerModule(SimpleModule()
                .addDeserializer(SarifLocationWrapper::class.java, SarifLocationWrapperDeserializer())
            )

        private const val defaultSchema =
            "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json"
        private const val defaultVersion =
            "2.1.0"

        fun empty() =
            Sarif(defaultSchema, defaultVersion, listOf())

        fun fromRun(run: SarifRun) =
            Sarif(defaultSchema, defaultVersion, listOf(run))

        fun fromJson(reportInJson: String): Sarif =
            jsonMapper.readValue(reportInJson)
    }

    fun toJson(): String =
        jsonMapper
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(this)

    @JsonIgnore
    fun getAllResults(): List<SarifResult> =
        runs.flatMap { it.results }
}

/**
 * [Documentation](https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning#run-object)
 */
data class SarifRun(
    val tool: SarifTool,
    val results: List<SarifResult>
)

// Tool

/**
 * Contains information about the tool that generated the SARIF report
 */
data class SarifTool(
    val driver: SarifToolComponent
) {
    companion object {
        private const val defaultName = "UnitTestBot"
        private const val defaultOrganization = "utbot.org"
        private const val defaultVersion = "1.0"

        fun fromRules(rules: List<SarifRule>) = SarifTool(
            driver = SarifToolComponent(
                defaultName,
                defaultOrganization,
                defaultVersion,
                rules
            )
        )
    }
}

data class SarifToolComponent(
    val name: String,
    val organization: String,
    val version: String,
    val rules: List<SarifRule>,
)

/**
 * [Documentation](https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning#reportingdescriptor-object)
 */
data class SarifRule(
    val id: String,
    val name: String,
    val shortDescription: Description,
    val fullDescription: Description,
    val help: Help
) {

    data class Description(
        val text: String
    )

    data class Help(
        val text: String,
        val markdown: String? = null
    )
}

// Results

/**
 * [Documentation](https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning#result-object)
 */
data class SarifResult(
    val ruleId: String,
    val level: Level,
    val message: Message,
    val locations: List<SarifLocationWrapper> = listOf(),
    val relatedLocations: List<SarifRelatedPhysicalLocationWrapper> = listOf(),
    val codeFlows: List<SarifCodeFlow> = listOf()
) {
    /**
     * Returns the total number of locations in all [codeFlows].
     */
    fun totalCodeFlowLocations() =
        codeFlows.sumOf { codeFlow ->
            codeFlow.threadFlows.sumOf { threadFlow ->
                threadFlow.locations.size
            }
        }
}

/**
 * The severity of the result. "Error" for detected unchecked exceptions.
 */
@Suppress("unused")
enum class Level(@get:JsonValue val value: String) {
    None("none"),
    Note("note"),
    Warning("warning"),
    Error("error")
}

/**
 * The message shown to the user, contains a short description of the result (detected error)
 */
data class Message(
    val text: String,
    val markdown: String? = null
)

// location

/**
 * [Documentation](https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning#location-object)
 */
sealed class SarifLocationWrapper

data class SarifPhysicalLocationWrapper(
    val physicalLocation: SarifPhysicalLocation // this name used in the SarifLocationWrapperDeserializer
) : SarifLocationWrapper()

data class SarifLogicalLocationsWrapper(
    val logicalLocations: List<SarifLogicalLocation> // this name used in the SarifLocationWrapperDeserializer
) : SarifLocationWrapper()

/**
 * Custom JSON deserializer for sealed class [SarifLocationWrapper].
 * Returns [SarifPhysicalLocationWrapper] or [SarifLogicalLocationsWrapper].
 */
class SarifLocationWrapperDeserializer : JsonDeserializer<SarifLocationWrapper>() {
    override fun deserialize(jp: JsonParser, context: DeserializationContext?): SarifLocationWrapper {
        val node: JsonNode = jp.codec.readTree(jp)
        val isPhysicalLocation = node.get("physicalLocation") != null // field name
        val isLogicalLocations = node.get("logicalLocations") != null // field name
        return when {
            isPhysicalLocation -> {
                jacksonObjectMapper().readValue<SarifPhysicalLocationWrapper>(node.toString())
            }
            isLogicalLocations -> {
                return jacksonObjectMapper().readValue<SarifLogicalLocationsWrapper>(node.toString())
            }
            else -> error("SarifLocationWrapperDeserializer: Cannot parse ${node.toPrettyString()}")
        }
    }
}

/**
 * [Documentation](https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning#physicallocation-object)
 */
data class SarifPhysicalLocation(
    val artifactLocation: SarifArtifact,
    val region: SarifRegion
)

data class SarifArtifact(
    val uri: String,
    val uriBaseId: String = "%SRCROOT%"
)

/**
 * All fields should be one-based.
 */
data class SarifRegion(
    val startLine: Int,
    val endLine: Int? = null,
    val startColumn: Int? = null,
    val endColumn: Int? = null
) {
    companion object {
        /**
         * Makes [startColumn] the first non-whitespace character in [startLine] in the [text].
         * If the [text] contains less than [startLine] lines, [startColumn] == null.
         * @param startLine should be one-based
         */
        fun withStartLine(text: String, startLine: Int): SarifRegion {
            val neededLine = text.split('\n').getOrNull(startLine - 1) // to zero-based
            val startColumn = neededLine?.run {
                takeWhile { it.toString().isBlank() }.length + 1 // to one-based
            }
            val safeStartLine = if (startLine < 1) {
                logger.warn { "For some reason startLine < 1, so now it is equal to 1" }
                1 // we don't want to fail, so just set the line number to 1
            } else {
                startLine
            }
            return SarifRegion(startLine = safeStartLine, startColumn = startColumn)
        }
    }
}

// logical locations

/**
 * [Documentation](https://github.com/microsoft/sarif-tutorials/blob/main/docs/2-Basics.md#physical-and-logical-locations)
 */
data class SarifLogicalLocation(
    val fullyQualifiedName: String
)

// related locations

/**
 * Used to attach links to the files with generated tests
 */
data class SarifRelatedPhysicalLocationWrapper(
    val id: Int,
    val physicalLocation: SarifPhysicalLocation
)

// code flow

/**
 * Used to represent the exception stack trace
 * - [Documentation (last item)](https://docs.github.com/en/code-security/code-scanning/integrating-with-code-scanning/sarif-support-for-code-scanning#result-object)
 */
data class SarifCodeFlow(
    val threadFlows: List<SarifThreadFlow>
) {
    companion object {
        fun fromLocations(locations: List<SarifFlowLocationWrapper>): SarifCodeFlow =
            SarifCodeFlow(
                threadFlows = listOf( // only one thread
                    SarifThreadFlow(locations)
                )
            )
    }
}

data class SarifThreadFlow(
    val locations: List<SarifFlowLocationWrapper>
)

data class SarifFlowLocationWrapper(
    val location: SarifFlowLocation
)

data class SarifFlowLocation(
    val message: Message,
    val physicalLocation: SarifPhysicalLocation
)
