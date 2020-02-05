package de.zalando.zally.ruleset.zalando

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.io.Resources
import com.typesafe.config.Config
import de.zalando.zally.core.EMPTY_JSON_POINTER
import de.zalando.zally.core.JsonSchemaValidator
import de.zalando.zally.core.ObjectTreeReader
import de.zalando.zally.rule.api.Check
import de.zalando.zally.rule.api.Context
import de.zalando.zally.rule.api.Rule
import de.zalando.zally.rule.api.Severity
import de.zalando.zally.rule.api.Violation
import de.zalando.zally.ruleset.zalando.UseOpenApiRule.OpenApiVersion.OPENAPI3
import de.zalando.zally.ruleset.zalando.UseOpenApiRule.OpenApiVersion.SWAGGER
import org.slf4j.LoggerFactory
import java.net.URL

@Rule(
    ruleSet = ZalandoRuleSet::class,
    id = "101",
    severity = Severity.MUST,
    title = "Provide API Specification using OpenAPI"
)
class UseOpenApiRule(rulesConfig: Config) {

    private enum class OpenApiVersion { SWAGGER, OPENAPI3 }

    private val log = LoggerFactory.getLogger(UseOpenApiRule::class.java)

    val description = "Given file is not OpenAPI 2.0 compliant."

    private val jsonSchemaValidators: Map<OpenApiVersion, JsonSchemaValidator>

    init {
        jsonSchemaValidators = getSchemaValidators(rulesConfig.getConfig(javaClass.simpleName))
    }

    @Check(severity = Severity.MUST)
    fun validateSchema(spec: JsonNode): List<Violation> {
        val openApi3Spec = spec.get("swagger") == null
        val currentVersion = if (openApi3Spec) OPENAPI3 else SWAGGER

        val swaggerValidator = jsonSchemaValidators[SWAGGER]
        val openApi3Validator = jsonSchemaValidators[OPENAPI3]

        return when {
            openApi3Spec -> openApi3Validator?.validate(spec).orEmpty()
            else -> swaggerValidator?.validate(spec).orEmpty()
        }.map {
            Violation("Does not match ${currentVersion.name.toLowerCase()} schema: ${it.description}", it.pointer)
        }
    }

    @Check(severity = Severity.MUST)
    fun checkIfTheFormatIsYAML(context: Context): Violation? {
        // at this point the api specification has been already parsed successfully
        // -> the source is either a valid YAML or JSON format
        // -> JSON must start with '{' and end with '}'
        val cleanedUpSource = context.source.trim()
        return if (cleanedUpSource.startsWith("{") && cleanedUpSource.endsWith("}")) {
            context.violation("must use YAML format", EMPTY_JSON_POINTER)
        } else {
            null
        }
    }

    private fun getSchemaValidators(ruleConfig: Config): Map<OpenApiVersion, JsonSchemaValidator> {
        val defaultSchemaRedirects = mapOf(
            "http://json-schema.org/draft-04/schema" to "schemas/json-schema.json",
            "http://swagger.io/v2/schema.json" to "schemas/${SWAGGER.name.toLowerCase()}-schema.json",
            "http://openapis.org/v3/schema.json" to "schemas/${OPENAPI3.name.toLowerCase()}-schema.json",
            "https://spec.openapis.org/oas/3.0/schema/2019-04-02" to "schemas/${OPENAPI3.name.toLowerCase()}-schema.json")
            .mapValues { (_, name) -> Resources.getResource(name).toString() }

        val reader = ObjectTreeReader()

        return OpenApiVersion
            .values()
            .map { version ->
                version to try {
                    val url = URL(ruleConfig.getString("schema_urls.${version.name.toLowerCase()}"))

                    val schema = reader.read(url)
                        .apply {
                            // to avoid resolving the `id` property of the schema by the validator
                            this as ObjectNode
                            remove("id")
                        }

                    JsonSchemaValidator(schema)
                } catch (e: Exception) {
                    log.warn("Unable to load swagger schemas: ${e.message}. Using default schemas instead.")

                    val url = Resources.getResource("schemas/${version.name.toLowerCase()}-schema.json")
                    val schema = reader.read(url)
                    JsonSchemaValidator(schema, defaultSchemaRedirects)
                }
            }
            .toMap()
    }
}
