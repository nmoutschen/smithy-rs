/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators.protocol

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.model.knowledge.OperationIndex
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.protocoltests.traits.AppliesTo
import software.amazon.smithy.protocoltests.traits.HttpMessageTestCase
import software.amazon.smithy.protocoltests.traits.HttpRequestTestCase
import software.amazon.smithy.protocoltests.traits.HttpRequestTestsTrait
import software.amazon.smithy.protocoltests.traits.HttpResponseTestCase
import software.amazon.smithy.protocoltests.traits.HttpResponseTestsTrait
import software.amazon.smithy.rust.codegen.rustlang.Attribute
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.RustMetadata
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.escape
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.withBlock
import software.amazon.smithy.rust.codegen.server.smithy.ServerCargoDependency
import software.amazon.smithy.rust.codegen.server.smithy.protocols.ServerHttpProtocolGenerator
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.generators.Instantiator
import software.amazon.smithy.rust.codegen.smithy.generators.protocol.ProtocolSupport
import software.amazon.smithy.rust.codegen.testutil.TokioTest
import software.amazon.smithy.rust.codegen.util.dq
import software.amazon.smithy.rust.codegen.util.getTrait
import software.amazon.smithy.rust.codegen.util.hasStreamingMember
import software.amazon.smithy.rust.codegen.util.hasTrait
import software.amazon.smithy.rust.codegen.util.inputShape
import software.amazon.smithy.rust.codegen.util.orNull
import software.amazon.smithy.rust.codegen.util.outputShape
import software.amazon.smithy.rust.codegen.util.toSnakeCase
import java.util.logging.Logger

/**
 * Generate protocol tests for an operation
 */
class ServerProtocolTestGenerator(
    private val codegenContext: CodegenContext,
    private val protocolSupport: ProtocolSupport,
    private val operationShape: OperationShape,
    private val writer: RustWriter
) {
    private val logger = Logger.getLogger(javaClass.name)

    private val model = codegenContext.model
    private val inputShape = operationShape.inputShape(codegenContext.model)
    private val outputShape = operationShape.outputShape(codegenContext.model)
    private val symbolProvider = codegenContext.symbolProvider
    private val operationSymbol = symbolProvider.toSymbol(operationShape)
    private val operationIndex = OperationIndex.of(codegenContext.model)
    private val operationImplementationName = "${operationSymbol.name}${ServerHttpProtocolGenerator.OPERATION_OUTPUT_WRAPPER_SUFFIX}"
    private val operationErrorName = "crate::error::${operationSymbol.name}Error"

    private val instantiator = with(codegenContext) {
        Instantiator(symbolProvider, model, runtimeConfig)
    }

    private val codegenScope = arrayOf(
        "Bytes" to RuntimeType.Bytes,
        "SmithyHttp" to CargoDependency.SmithyHttp(codegenContext.runtimeConfig).asType(),
        "Http" to CargoDependency.Http.asType(),
        "Hyper" to CargoDependency.Hyper.asType(),
        "AxumCore" to ServerCargoDependency.AxumCore.asType(),
        "SmithyHttpServer" to CargoDependency.SmithyHttpServer(codegenContext.runtimeConfig).asType(),
    )

    sealed class TestCase {
        abstract val testCase: HttpMessageTestCase

        data class RequestTest(override val testCase: HttpRequestTestCase, val targetShape: StructureShape) :
            TestCase()
        data class ResponseTest(override val testCase: HttpResponseTestCase, val targetShape: StructureShape) :
            TestCase()
    }

    fun render() {
        val requestTests = operationShape.getTrait<HttpRequestTestsTrait>()
            ?.getTestCasesFor(AppliesTo.SERVER).orEmpty().map { TestCase.RequestTest(it, inputShape) }
        val responseTests = operationShape.getTrait<HttpResponseTestsTrait>()
            ?.getTestCasesFor(AppliesTo.SERVER).orEmpty().map { TestCase.ResponseTest(it, outputShape) }

        val errorTests = operationIndex.getErrors(operationShape).flatMap { error ->
            val testCases = error.getTrait<HttpResponseTestsTrait>()?.testCases.orEmpty()
            testCases.map { TestCase.ResponseTest(it, error) }
        }
        val allTests: List<TestCase> = (requestTests + responseTests + errorTests).filterMatching()

        if (allTests.isNotEmpty()) {
            val operationName = operationSymbol.name
            val testModuleName = "server_${operationName.toSnakeCase()}_test"
            val moduleMeta = RustMetadata(
                public = false,
                additionalAttributes = listOf(
                    Attribute.Cfg("test"),
                    Attribute.Custom("allow(unreachable_code, unused_variables)")
                )
            )
            writer.withModule(testModuleName, moduleMeta) {
                Attribute.Cfg("test").render(this)
                rustTemplate("use #{PrettyAssertions}::assert_eq;",
                    "PrettyAssertions" to CargoDependency.PrettyAssertions.asType())
                renderAllTestCases(allTests)
            }
        }
    }

    private fun RustWriter.renderAllTestCases(allTests: List<TestCase>) {
        allTests.forEach {
            renderTestCaseBlock(it.testCase, this) {
                when (it) {
                    is TestCase.RequestTest -> this.renderHttpRequestTestCase(it.testCase, it.targetShape)
                    is TestCase.ResponseTest -> this.renderHttpResponseTestCase(it.testCase, it.targetShape)
                }
            }
        }
    }

    /**
     * Filter out test cases that are disabled or don't match the service protocol
     */
    private fun List<TestCase>.filterMatching(): List<TestCase> {
        return if (RunOnly.isNullOrEmpty()) {
            this.filter { testCase ->
                testCase.testCase.protocol == codegenContext.protocol &&
                    !DisableTests.contains(testCase.testCase.id)
            }
        } else {
            this.filter { RunOnly.contains(it.testCase.id) }
        }
    }

    private fun renderTestCaseBlock(
        testCase: HttpMessageTestCase,
        testModuleWriter: RustWriter,
        block: RustWriter.() -> Unit
    ) {
        testModuleWriter.setNewlinePrefix("/// ")
        testCase.documentation.map {
            testModuleWriter.writeWithNoFormatting(it)
        }

        testModuleWriter.write("Test ID: ${testCase.id}")
        testModuleWriter.setNewlinePrefix("")
        TokioTest.render(testModuleWriter)

        val action = when (testCase) {
            is HttpResponseTestCase -> Action.Response
            is HttpRequestTestCase -> Action.Request
            else -> throw CodegenException("unknown test case type")
        }
        if (expectFail(testCase)) {
            testModuleWriter.writeWithNoFormatting("#[should_panic]")
        }
        val fnName = when (action) {
            is Action.Response -> "_response"
            is Action.Request -> "_request"
        }
        testModuleWriter.rustBlock("async fn ${testCase.id.toSnakeCase()}$fnName()") {
            block(this)
        }
    }

    private fun RustWriter.renderHttpRequestTestCase(
        httpRequestTestCase: HttpRequestTestCase,
        inputShape: StructureShape,
    ) {
        if (!protocolSupport.requestDeserialization) {
            rust("/* test case disabled for this protocol (not yet supported) */")
            return
        }
        writeInline("let expected =")
        instantiator.render(this, inputShape, httpRequestTestCase.params)
        write(";")
        httpRequestTestCase.body.orNull()?.also { body ->
            rustTemplate(
                """
                ##[allow(unused_mut)] let mut http_request = http::Request::builder()
                    .uri(${httpRequestTestCase.uri.dq()})
                    .body(#{SmithyHttpServer}::Body::from(#{Bytes}::from_static(b${body.dq()}))).unwrap();
                """,
                *codegenScope
            )
            if (!httpRequestTestCase.bodyMediaType.isEmpty()) {
                rust("""http_request.headers_mut().insert("Content-Type", http::header::HeaderValue::from_static(${httpRequestTestCase.bodyMediaType.get().dq()}));""")
            }
        }
        if (!httpRequestTestCase.queryParams.isEmpty()) {
            val queryParams = httpRequestTestCase.queryParams.joinToString(separator = "&")
            rust("""*http_request.uri_mut() = "${httpRequestTestCase.uri}?$queryParams".parse().unwrap();""")
        }
        httpRequestTestCase.host.orNull()?.also {
            rust("""todo!("endpoint trait not supported yet");""")
        }
        checkQueryParams(this, httpRequestTestCase.queryParams)
        checkForbidQueryParams(this, httpRequestTestCase.forbidQueryParams)
        checkRequiredQueryParams(this, httpRequestTestCase.requireQueryParams)
        checkHeaders(this, httpRequestTestCase.headers)
        checkForbidHeaders(this, httpRequestTestCase.forbidHeaders)
        checkRequiredHeaders(this, httpRequestTestCase.requireHeaders)
        if (protocolSupport.requestBodyDeserialization) {
            // "If no request body is defined, then no assertions are made about the body of the message."
            httpRequestTestCase.body.orNull()?.also { body ->
                checkBody(this, body, httpRequestTestCase)
            }
        }

        // Explicitly warn if the test case defined parameters that we aren't doing anything with
        with(httpRequestTestCase) {
            if (authScheme.isPresent) {
                logger.warning("Test case provided authScheme but this was ignored")
            }
            if (!httpRequestTestCase.vendorParams.isEmpty) {
                logger.warning("Test case provided vendorParams but these were ignored")
            }
        }
    }

    private fun HttpMessageTestCase.action(): Action = when (this) {
        is HttpRequestTestCase -> Action.Request
        is HttpResponseTestCase -> Action.Response
        else -> throw CodegenException("Unknown test case type")
    }

    private fun expectFail(testCase: HttpMessageTestCase): Boolean = ExpectFail.find {
        it.id == testCase.id && it.action == testCase.action() && it.service == codegenContext.serviceShape.id.toString()
    } != null

    private fun RustWriter.renderHttpResponseTestCase(
        testCase: HttpResponseTestCase,
        expectedShape: StructureShape
    ) {
        if (!protocolSupport.responseSerialization || (
            !protocolSupport.errorSerialization && expectedShape.hasTrait<ErrorTrait>()
            )
        ) {
            rust("/* test case disabled for this protocol (not yet supported) */")
            return
        }
        writeInline("let output =")
        instantiator.render(this, expectedShape, testCase.params)
        write(";")
        val operationImpl = if (operationShape.errors.isNotEmpty()) {
            if (expectedShape.hasTrait<ErrorTrait>()) {
                val variant = symbolProvider.toSymbol(expectedShape).name
                "$operationImplementationName::Error($operationErrorName::$variant(output))"
            } else {
                "$operationImplementationName::Output(output)"
            }
        } else {
            "$operationImplementationName(output)"
        }
        rustTemplate(
            """
            let output = super::$operationImpl;
            use #{AxumCore}::response::IntoResponse;
            let http_response = output.into_response();
            """,
            *codegenScope,
        )
        rust(
            """
            assert_eq!(
                http::StatusCode::from_u16(${testCase.code}).expect("invalid expected HTTP status code"),
                http_response.status()
            );
            """
        )
        checkHttpExtensions(this)
        if (!testCase.body.isEmpty()) {
            rustTemplate(
                """
                let body = #{Hyper}::body::to_bytes(http_response.into_body()).await.expect("unable to extract body to bytes");
                assert_eq!(${escape(testCase.body.get()).dq()}, body);
                """,
                *codegenScope
            )
        }
    }

    private fun checkRequiredHeaders(rustWriter: RustWriter, requireHeaders: List<String>) {
        basicCheck(requireHeaders, rustWriter, "required_headers", "require_headers")
    }

    private fun checkForbidHeaders(rustWriter: RustWriter, forbidHeaders: List<String>) {
        basicCheck(forbidHeaders, rustWriter, "forbidden_headers", "forbid_headers")
    }

    private fun checkBody(rustWriter: RustWriter, body: String, testCase: HttpRequestTestCase) {
        val operationName = "${operationSymbol.name}${ServerHttpProtocolGenerator.OPERATION_INPUT_WRAPPER_SUFFIX}"
        rustWriter.rustTemplate(
            """
            use #{AxumCore}::extract::FromRequest;
            let mut http_request = #{AxumCore}::extract::RequestParts::new(http_request);
            let input_wrapper = super::$operationName::from_request(&mut http_request).await.expect("failed to parse request");
            let input = input_wrapper.0;
            """,
            *codegenScope,
        )
        if (operationShape.outputShape(model).hasStreamingMember(model)) {
            rustWriter.rust("""todo!("streaming types aren't supported yet");""")
        } else {
            rustWriter.rust("assert_eq!(input, expected);")
        }
    }

    private fun checkHttpExtensions(rustWriter: RustWriter) {
        rustWriter.rust(
            """
            let request_extensions = http_response.extensions().get::<aws_smithy_http_server::RequestExtensions>().expect("extension `RequestExtensions` not found");
            assert_eq!(request_extensions.namespace, ${operationShape.id.getNamespace().dq()});
            assert_eq!(request_extensions.operation_name, ${operationSymbol.name.dq()});
            """.trimIndent()
        )
    }

    private fun checkHeaders(rustWriter: RustWriter, headers: Map<String, String>) {
        if (headers.isEmpty()) {
            return
        }
        val variableName = "expected_headers"
        rustWriter.withBlock("let $variableName = [", "];") {
            write(
                headers.entries.joinToString(",") {
                    "(${it.key.dq()}, ${it.value.dq()})"
                }
            )
        }
        assertOk(rustWriter) {
            write(
                "#T(&http_request, $variableName)",
                RuntimeType.ProtocolTestHelper(codegenContext.runtimeConfig, "validate_headers")
            )
        }
    }

    private fun checkRequiredQueryParams(
        rustWriter: RustWriter,
        requiredParams: List<String>
    ) = basicCheck(requiredParams, rustWriter, "required_params", "require_query_params")

    private fun checkForbidQueryParams(
        rustWriter: RustWriter,
        forbidParams: List<String>
    ) = basicCheck(forbidParams, rustWriter, "forbid_params", "forbid_query_params")

    private fun checkQueryParams(
        rustWriter: RustWriter,
        queryParams: List<String>
    ) = basicCheck(queryParams, rustWriter, "expected_query_params", "validate_query_string")

    private fun basicCheck(
        params: List<String>,
        rustWriter: RustWriter,
        variableName: String,
        checkFunction: String
    ) {
        if (params.isEmpty()) {
            return
        }
        rustWriter.withBlock("let $variableName = ", ";") {
            strSlice(this, params)
        }
        assertOk(rustWriter) {
            write(
                "#T(&http_request, $variableName)",
                RuntimeType.ProtocolTestHelper(codegenContext.runtimeConfig, checkFunction)
            )
        }
    }

    /**
     * wraps `inner` in a call to `aws_smithy_protocol_test::assert_ok`, a convenience wrapper
     * for pretty prettying protocol test helper results
     */
    private fun assertOk(rustWriter: RustWriter, inner: RustWriter.() -> Unit) {
        rustWriter.write("#T(", RuntimeType.ProtocolTestHelper(codegenContext.runtimeConfig, "assert_ok"))
        inner(rustWriter)
        rustWriter.write(");")
    }

    private fun strSlice(writer: RustWriter, args: List<String>) {
        writer.withBlock("&[", "]") {
            write(args.joinToString(",") { it.dq() })
        }
    }

    companion object {
        sealed class Action {
            object Request : Action()
            object Response : Action()
        }

        data class FailingTest(val service: String, val id: String, val action: Action)

        // These tests fail due to shortcomings in our implementation.
        // These could be configured via runtime configuration, but since this won't be long-lasting,
        // it makes sense to do the simplest thing for now.
        // The test will _fail_ if these pass, so we will discover & remove if we fix them by accident
        private val JsonRpc10 = "aws.protocoltests.json10#JsonRpc10"
        private val AwsJson11 = "aws.protocoltests.json#JsonProtocol"
        private val RestJson = "aws.protocoltests.restjson#RestJson"
        private val RestXml = "aws.protocoltests.restxml#RestXml"
        private val AwsQuery = "aws.protocoltests.query#AwsQuery"
        private val Ec2Query = "aws.protocoltests.ec2#AwsEc2"
        private val ExpectFail = setOf<FailingTest>(
            FailingTest(RestJson, "RestJsonAllQueryStringTypes", Action.Request),
            FailingTest(RestJson, "RestJsonQueryStringMap", Action.Request),
            FailingTest(RestJson, "RestJsonQueryStringEscaping", Action.Request),
            FailingTest(RestJson, "RestJsonSupportsNaNFloatQueryValues", Action.Request),
            FailingTest(RestJson, "RestJsonSupportsInfinityFloatQueryValues", Action.Request),
            FailingTest(RestJson, "RestJsonSupportsNegativeInfinityFloatQueryValues", Action.Request),
            FailingTest(RestJson, "DocumentOutput", Action.Response),
            FailingTest(RestJson, "DocumentOutputString", Action.Response),
            FailingTest(RestJson, "DocumentOutputNumber", Action.Response),
            FailingTest(RestJson, "DocumentOutputBoolean", Action.Response),
            FailingTest(RestJson, "DocumentOutputArray", Action.Response),
            FailingTest(RestJson, "DocumentTypeAsPayloadInput", Action.Request),
            FailingTest(RestJson, "DocumentTypeAsPayloadInputString", Action.Request),
            FailingTest(RestJson, "DocumentTypeAsPayloadOutput", Action.Response),
            FailingTest(RestJson, "DocumentTypeAsPayloadOutputString", Action.Response),
            FailingTest(RestJson, "RestJsonEmptyInputAndEmptyOutput", Action.Response),
            FailingTest(RestJson, "RestJsonEndpointTrait", Action.Request),
            FailingTest(RestJson, "RestJsonEndpointTraitWithHostLabel", Action.Request),
            FailingTest(RestJson, "RestJsonInvalidGreetingError", Action.Response),
            FailingTest(RestJson, "RestJsonComplexErrorWithNoMessage", Action.Response),
            FailingTest(RestJson, "RestJsonFooErrorUsingCode", Action.Response),
            FailingTest(RestJson, "RestJsonFooErrorUsingCodeAndNamespace", Action.Response),
            FailingTest(RestJson, "RestJsonFooErrorUsingCodeUriAndNamespace", Action.Response),
            FailingTest(RestJson, "RestJsonFooErrorWithDunderType", Action.Response),
            FailingTest(RestJson, "RestJsonFooErrorWithDunderTypeAndNamespace", Action.Response),
            FailingTest(RestJson, "RestJsonFooErrorWithDunderTypeUriAndNamespace", Action.Response),
            FailingTest(RestJson, "RestJsonHttpChecksumRequired", Action.Request),
            FailingTest(RestJson, "EnumPayloadRequest", Action.Request),
            FailingTest(RestJson, "EnumPayloadResponse", Action.Response),
            FailingTest(RestJson, "RestJsonHttpPayloadTraitsWithBlob", Action.Request),
            FailingTest(RestJson, "RestJsonHttpPayloadTraitsWithNoBlobBody", Action.Request),
            FailingTest(RestJson, "RestJsonHttpPayloadTraitsWithBlobAcceptsAllContentTypes", Action.Request),
            FailingTest(RestJson, "RestJsonHttpPayloadTraitsWithBlobAcceptsAllAccepts", Action.Request),
            FailingTest(RestJson, "RestJsonHttpPayloadTraitsWithBlob", Action.Response),
            FailingTest(RestJson, "RestJsonHttpPayloadTraitsWithNoBlobBody", Action.Response),
            FailingTest(RestJson, "RestJsonHttpPayloadTraitsWithMediaTypeWithBlob", Action.Request),
            FailingTest(RestJson, "RestJsonHttpPayloadTraitsWithMediaTypeWithBlob", Action.Response),
            FailingTest(RestJson, "RestJsonHttpPayloadWithStructure", Action.Request),
            FailingTest(RestJson, "RestJsonHttpPayloadWithStructure", Action.Response),
            FailingTest(RestJson, "RestJsonHttpPrefixHeadersArePresent", Action.Request),
            FailingTest(RestJson, "RestJsonHttpPrefixHeadersAreNotPresent", Action.Request),
            FailingTest(RestJson, "RestJsonHttpPrefixHeadersArePresent", Action.Response),
            FailingTest(RestJson, "HttpPrefixHeadersResponse", Action.Response),
            FailingTest(RestJson, "RestJsonSupportsNaNFloatLabels", Action.Request),
            FailingTest(RestJson, "RestJsonHttpResponseCode", Action.Response),
            FailingTest(RestJson, "StringPayloadRequest", Action.Request),
            FailingTest(RestJson, "StringPayloadResponse", Action.Response),
            FailingTest(RestJson, "RestJsonIgnoreQueryParamsInResponse", Action.Response),
            FailingTest(RestJson, "RestJsonInputAndOutputWithStringHeaders", Action.Request),
            FailingTest(RestJson, "RestJsonInputAndOutputWithNumericHeaders", Action.Request),
            FailingTest(RestJson, "RestJsonInputAndOutputWithBooleanHeaders", Action.Request),
            FailingTest(RestJson, "RestJsonInputAndOutputWithTimestampHeaders", Action.Request),
            FailingTest(RestJson, "RestJsonInputAndOutputWithEnumHeaders", Action.Request),
            FailingTest(RestJson, "RestJsonSupportsNaNFloatHeaderInputs", Action.Request),
            FailingTest(RestJson, "RestJsonSupportsInfinityFloatHeaderInputs", Action.Request),
            FailingTest(RestJson, "RestJsonSupportsNegativeInfinityFloatHeaderInputs", Action.Request),
            FailingTest(RestJson, "RestJsonInputAndOutputWithStringHeaders", Action.Response),
            FailingTest(RestJson, "RestJsonInputAndOutputWithNumericHeaders", Action.Response),
            FailingTest(RestJson, "RestJsonInputAndOutputWithBooleanHeaders", Action.Response),
            FailingTest(RestJson, "RestJsonInputAndOutputWithTimestampHeaders", Action.Response),
            FailingTest(RestJson, "RestJsonInputAndOutputWithEnumHeaders", Action.Response),
            FailingTest(RestJson, "RestJsonSupportsNaNFloatHeaderOutputs", Action.Response),
            FailingTest(RestJson, "RestJsonSupportsInfinityFloatHeaderOutputs", Action.Response),
            FailingTest(RestJson, "RestJsonSupportsNegativeInfinityFloatHeaderOutputs", Action.Response),
            FailingTest(RestJson, "RestJsonJsonBlobs", Action.Response),
            FailingTest(RestJson, "RestJsonJsonEnums", Action.Response),
            FailingTest(RestJson, "RestJsonLists", Action.Response),
            FailingTest(RestJson, "RestJsonListsEmpty", Action.Response),
            FailingTest(RestJson, "RestJsonListsSerializeNull", Action.Response),
            FailingTest(RestJson, "RestJsonJsonMaps", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializesNullMapValues", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializesZeroValuesInMaps", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializesSparseSetMap", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializesDenseSetMap", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializesSparseSetMapAndRetainsNull", Action.Response),
            FailingTest(RestJson, "RestJsonJsonTimestamps", Action.Response),
            FailingTest(RestJson, "RestJsonJsonTimestampsWithDateTimeFormat", Action.Response),
            FailingTest(RestJson, "RestJsonJsonTimestampsWithEpochSecondsFormat", Action.Response),
            FailingTest(RestJson, "RestJsonJsonTimestampsWithHttpDateFormat", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializeStringUnionValue", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializeBooleanUnionValue", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializeNumberUnionValue", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializeBlobUnionValue", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializeTimestampUnionValue", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializeEnumUnionValue", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializeListUnionValue", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializeMapUnionValue", Action.Response),
            FailingTest(RestJson, "RestJsonDeserializeStructureUnionValue", Action.Response),
            FailingTest(RestJson, "MediaTypeHeaderInputBase64", Action.Request),
            FailingTest(RestJson, "MediaTypeHeaderOutputBase64", Action.Response),
            FailingTest(RestJson, "RestJsonNoInputAllowsAccept", Action.Request),
            FailingTest(RestJson, "RestJsonNoInputAndNoOutput", Action.Response),
            FailingTest(RestJson, "RestJsonNoInputAndOutputAllowsAccept", Action.Request),
            FailingTest(RestJson, "RestJsonNoInputAndOutputWithJson", Action.Response),
            FailingTest(RestJson, "RestJsonNullAndEmptyHeaders", Action.Response),
            FailingTest(RestJson, "RestJsonRecursiveShapes", Action.Response),
            FailingTest(RestJson, "RestJsonSimpleScalarProperties", Action.Request),
            FailingTest(RestJson, "RestJsonSupportsNaNFloatInputs", Action.Request),
            FailingTest(RestJson, "RestJsonSimpleScalarProperties", Action.Response),
            FailingTest(RestJson, "RestJsonServersDontSerializeNullStructureValues", Action.Response),
            FailingTest(RestJson, "RestJsonSupportsNaNFloatInputs", Action.Response),
            FailingTest(RestJson, "RestJsonSupportsInfinityFloatInputs", Action.Response),
            FailingTest(RestJson, "RestJsonSupportsNegativeInfinityFloatInputs", Action.Response),
            FailingTest(RestJson, "RestJsonStreamingTraitsWithBlob", Action.Request),
            FailingTest(RestJson, "RestJsonStreamingTraitsWithNoBlobBody", Action.Request),
            FailingTest(RestJson, "RestJsonStreamingTraitsWithBlob", Action.Response),
            FailingTest(RestJson, "RestJsonStreamingTraitsWithNoBlobBody", Action.Response),
            FailingTest(RestJson, "RestJsonStreamingTraitsRequireLengthWithBlob", Action.Request),
            FailingTest(RestJson, "RestJsonStreamingTraitsRequireLengthWithNoBlobBody", Action.Request),
            FailingTest(RestJson, "RestJsonStreamingTraitsRequireLengthWithBlob", Action.Response),
            FailingTest(RestJson, "RestJsonStreamingTraitsRequireLengthWithNoBlobBody", Action.Response),
            FailingTest(RestJson, "RestJsonStreamingTraitsWithMediaTypeWithBlob", Action.Request),
            FailingTest(RestJson, "RestJsonStreamingTraitsWithMediaTypeWithBlob", Action.Response),
            FailingTest(RestJson, "RestJsonTestBodyStructure", Action.Request),
            FailingTest(RestJson, "RestJsonHttpWithEmptyBody", Action.Request),
            FailingTest(RestJson, "RestJsonHttpWithHeaderMemberNoModeledBody", Action.Request),
            FailingTest(RestJson, "RestJsonHttpWithEmptyBlobPayload", Action.Request),
            FailingTest(RestJson, "RestJsonTestPayloadBlob", Action.Request),
            FailingTest(RestJson, "RestJsonHttpWithEmptyStructurePayload", Action.Request),
            FailingTest(RestJson, "RestJsonTestPayloadStructure", Action.Request),
            FailingTest(RestJson, "RestJsonHttpWithHeadersButNoPayload", Action.Request),
            FailingTest(RestJson, "RestJsonTimestampFormatHeaders", Action.Request),
            FailingTest(RestJson, "RestJsonTimestampFormatHeaders", Action.Response),
            FailingTest("com.amazonaws.s3#AmazonS3", "GetBucketLocationUnwrappedOutput", Action.Response),
            FailingTest("com.amazonaws.s3#AmazonS3", "S3DefaultAddressing", Action.Request),
            FailingTest("com.amazonaws.s3#AmazonS3", "S3VirtualHostAddressing", Action.Request),
            FailingTest("com.amazonaws.s3#AmazonS3", "S3PathAddressing", Action.Request),
            FailingTest("com.amazonaws.s3#AmazonS3", "S3VirtualHostDualstackAddressing", Action.Request),
            FailingTest("com.amazonaws.s3#AmazonS3", "S3VirtualHostAccelerateAddressing", Action.Request),
            FailingTest("com.amazonaws.s3#AmazonS3", "S3VirtualHostDualstackAccelerateAddressing", Action.Request),
            FailingTest("com.amazonaws.s3#AmazonS3", "S3OperationAddressingPreferred", Action.Request),
        )
        private val RunOnly: Set<String>? = null

        // These tests are not even attempted to be generated, either because they will not compile
        // or because they are flaky
        private val DisableTests = setOf<String>()
    }
}
