package edu.bu.archive.adapter.out.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.bu.archive.application.ai.AiProviderException;
import edu.bu.archive.config.AiProperties;
import edu.bu.archive.domain.model.ai.AiRequest;
import edu.bu.archive.domain.model.ai.AiResponse;
import edu.bu.archive.domain.model.ai.AwardAiContext;
import edu.bu.archive.domain.model.ai.AwardAiContextChanges;
import edu.bu.archive.domain.model.ai.AwardAiContextRecord;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OpenAiProviderTest {

    private static final String API_KEY = "test-api-key-value";

    private ObjectMapper objectMapper;
    private HttpClient httpClient;
    private HttpResponse<String> httpResponse;
    private OpenAiProvider provider;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        httpClient = mock(HttpClient.class);
        httpResponse = mock(HttpResponse.class);
        provider = new OpenAiProvider(
                objectMapper,
                httpClient,
                properties(),
                API_KEY
        );
    }

    @Test
    void sendsStrictStructuredAwardContextAndMapsResponse()
            throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(successResponse());
        when(httpClient.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(httpResponse);

        AiResponse response = provider.generate(request());

        assertThat(response.overview())
                .isEqualTo("Award A-100 has one archived record.");
        assertThat(response.notableChanges())
                .containsExactly("Status changed.");
        assertThat(response.archiveAssessment())
                .isEqualTo("The supplied archive is internally consistent.");
        assertThat(response.citations()).singleElement()
                .satisfies(citation -> {
                    assertThat(citation.recordType())
                            .isEqualTo("award");
                    assertThat(citation.recordId())
                            .isEqualTo("101");
                    assertThat(citation.awardNumber())
                            .isEqualTo("A-100");
                    assertThat(citation.sequenceNumber())
                            .isEqualTo(2);
                });
        assertThat(response.provider()).isEqualTo("openai");
        assertThat(response.model()).isEqualTo("gpt-5");
        assertThat(response.inputTokenCount()).isEqualTo(120);
        assertThat(response.outputTokenCount()).isEqualTo(35);

        ArgumentCaptor<HttpRequest> requestCaptor =
                ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(
                requestCaptor.capture(),
                any(HttpResponse.BodyHandler.class)
        );
        HttpRequest sent = requestCaptor.getValue();
        assertThat(sent.uri().toString())
                .isEqualTo("https://api.openai.com/v1/responses");
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.timeout())
                .contains(Duration.ofSeconds(12));
        assertThat(sent.headers().firstValue("Authorization"))
                .contains("Bearer " + API_KEY);

        JsonNode body = objectMapper.readTree(
                provider.requestBody(request())
        );
        assertThat(body.path("model").asText())
                .isEqualTo("gpt-5");
        assertThat(body.path("store").asBoolean()).isFalse();
        assertThat(body.at("/text/format/type").asText())
                .isEqualTo("json_schema");
        assertThat(body.at("/text/format/strict").asBoolean())
                .isTrue();
        assertThat(body.at(
                "/text/format/schema/additionalProperties"
        ).asBoolean()).isFalse();
        assertThat(body.at(
                "/text/format/schema/properties/overview/type"
        ).asText()).isEqualTo("string");
        assertThat(body.at(
                "/text/format/schema/properties/notableChanges/type"
        ).asText()).isEqualTo("array");
        assertThat(body.at(
                "/text/format/schema/properties/archiveAssessment/type"
        ).asText()).isEqualTo("string");
        assertThat(body.at(
                "/text/format/schema/properties/currentRecord"
        ).isMissingNode()).isTrue();
        assertThat(body.at(
                "/text/format/schema/properties/timeline"
        ).isMissingNode()).isTrue();
        assertThat(body.at(
                "/text/format/schema/properties/citations"
                        + "/items/properties/recordType/enum/0"
        ).asText()).isEqualTo("award");
        assertThat(body.at("/input/0/content/0/text").asText())
                .contains("\"awardId\":101")
                .contains("\"awardNumber\":\"A-100\"")
                .contains("\"currentAwardId\":101")
                .contains("\"changes\":{")
                .containsOnlyOnce("\"awardNumber\":\"A-100\"")
                .doesNotContain("\"current\":")
                .doesNotContain("\"primaryCurrent\":")
                .doesNotContain(API_KEY);
    }

    @Test
    void rejectsNonSuccessfulResponsesWithoutLeakingDetails()
            throws Exception {
        when(httpResponse.statusCode()).thenReturn(401);
        when(httpResponse.body()).thenReturn(
                "{\"error\":{\"message\":\"secret diagnostics\"}}"
        );
        when(httpClient.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(httpResponse);

        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOf(AiProviderException.class)
                .hasMessage("OpenAI request failed with status 401")
                .hasMessageNotContaining("secret diagnostics")
                .hasMessageNotContaining(API_KEY);
    }

    @Test
    void sanitizesTimeoutFailures()
            throws Exception {
        when(httpClient.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
        )).thenThrow(new HttpTimeoutException("socket detail"));

        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOf(AiProviderException.class)
                .hasMessage(
                        "Timed out waiting for OpenAI Responses API"
                )
                .hasMessageNotContaining("socket detail")
                .hasMessageNotContaining(API_KEY);
    }

    @Test
    void appliesTheConfiguredConnectTimeout() {
        AiProperties properties = properties();
        properties.setOpenAiConnectTimeoutSeconds(7);

        HttpClient client =
                OpenAiProvider.createHttpClient(properties);

        assertThat(client.connectTimeout())
                .contains(Duration.ofSeconds(7));
    }

    @Test
    void rejectsMalformedOrMissingOutput()
            throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{not-json");
        when(httpClient.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(httpResponse);

        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOf(AiProviderException.class)
                .hasMessage("OpenAI returned malformed JSON");

        when(httpResponse.body()).thenReturn("""
                {
                  "status": "completed",
                  "output": []
                }
                """);

        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOf(AiProviderException.class)
                .hasMessage("OpenAI returned an invalid response");
    }

    @Test
    void rejectsMalformedCitationShape()
            throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(
                responseWithOutput("""
                        {
                          "summary": "Summary",
                          "citations": [
                            {
                              "recordType": "award",
                              "recordId": "101",
                              "awardNumber": "A-100"
                            }
                          ]
                        }
                        """)
        );
        when(httpClient.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(httpResponse);

        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOf(AiProviderException.class)
                .hasMessage("OpenAI returned an invalid response");
    }

    @Test
    void rejectsModelSuppliedDeterministicFields()
            throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(
                responseWithOutput("""
                        {
                          "overview": "Narrative",
                          "notableChanges": [],
                          "archiveAssessment": "Assessment",
                          "citations": [
                            {
                              "recordType": "award",
                              "recordId": "101",
                              "awardNumber": "A-100",
                              "sequenceNumber": 2
                            }
                          ],
                          "currentRecord": {
                            "status": "MODEL STATUS",
                            "sponsor": "MODEL SPONSOR",
                            "pi": "MODEL PI",
                            "amounts": 999999
                          },
                          "timeline": [
                            {"sequenceNumber": 999}
                          ]
                        }
                        """)
        );
        when(httpClient.send(
                any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)
        )).thenReturn(httpResponse);

        assertThatThrownBy(() -> provider.generate(request()))
                .isInstanceOf(AiProviderException.class)
                .hasMessage("OpenAI returned an invalid response");
    }

    private AiProperties properties() {
        AiProperties properties = new AiProperties();
        properties.setOpenAiModel("gpt-5");
        properties.setOpenAiBaseUrl(
                "https://api.openai.com/v1/"
        );
        properties.setOpenAiTimeoutSeconds(12);
        properties.setOpenAiConnectTimeoutSeconds(5);
        return properties;
    }

    private AiRequest request() {
        AwardAiContextRecord record =
                new AwardAiContextRecord(
                        101L,
                        2,
                        new AwardAiContextChanges(
                                "Archived title",
                                "Active",
                                "Final",
                                "Sponsor",
                                null,
                                "Lead unit",
                                LocalDate.of(2020, 1, 1),
                                null
                        ),
                        null
                );
        return new AiRequest(
                "Use only the supplied context.",
                new AwardAiContext(
                        "A-100",
                        101L,
                        List.of(record),
                        false
                )
        );
    }

    private String successResponse()
            throws Exception {
        return responseWithOutput("""
                {
                  "overview": "Award A-100 has one archived record.",
                  "notableChanges": ["Status changed."],
                  "archiveAssessment": "The supplied archive is internally consistent.",
                  "citations": [
                    {
                      "recordType": "award",
                      "recordId": "101",
                      "awardNumber": "A-100",
                      "sequenceNumber": 2
                    }
                  ]
                }
                """);
    }

    private String responseWithOutput(
            String output
    ) throws Exception {
        ObjectNode content = objectMapper.createObjectNode()
                .put("type", "output_text")
                .put("text", output);
        ObjectNode item = objectMapper.createObjectNode();
        item.set(
                "content",
                objectMapper.createArrayNode().add(content)
        );
        ObjectNode usage = objectMapper.createObjectNode()
                .put("input_tokens", 120)
                .put("output_tokens", 35);
        ObjectNode response = objectMapper.createObjectNode()
                .put("status", "completed");
        response.set(
                "output",
                objectMapper.createArrayNode().add(item)
        );
        response.set("usage", usage);
        return objectMapper.writeValueAsString(response);
    }
}
