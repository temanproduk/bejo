package com.bejo.voice.service;

import com.bejo.shared.exception.ServiceUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleSpeechToTextTest {

    private static final String URL = "https://speech.example.test/v1/speech:recognize";
    private final ObjectMapper json = new ObjectMapper();

    private MockRestServiceServer server;
    private GoogleSpeechToText speech;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        speech = new GoogleSpeechToText(builder.build(), URL, "latest_short",
                () -> new GoogleSpeechToText.Auth("tok-123", "my-project"));
    }

    @Test
    void asksForIndonesianWithTheMoneyWordsAsHintsAndTheClipAsBase64() throws Exception {
        byte[] pcm = {1, 2, 3, 4};
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-123"))
                .andExpect(header("x-goog-user-project", "my-project"))
                .andExpect(request -> {
                    JsonNode body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                    JsonNode config = body.get("config");
                    assertThat(config.get("languageCode").asText()).isEqualTo("id-ID");
                    assertThat(config.get("model").asText()).isEqualTo("latest_short");
                    assertThat(config.get("encoding").asText()).isEqualTo("LINEAR16");
                    assertThat(config.get("sampleRateHertz").asInt()).isEqualTo(16000);
                    assertThat(config.get("speechContexts").get(0).get("phrases").toString())
                            .contains("ribu", "juta", "ceban", "goceng");
                    assertThat(body.get("audio").get("content").asText()).isEqualTo(Base64.getEncoder().encodeToString(pcm));
                })
                .andRespond(withSuccess("{\"results\":[{\"alternatives\":[{\"transcript\":\"25000\",\"confidence\":0.9}]}]}",
                        MediaType.APPLICATION_JSON));

        Optional<SpeechToText.Transcript> heard = speech.transcribe(pcm, 16000);

        assertThat(heard).isPresent();
        assertThat(heard.get().text()).isEqualTo("25000");
        assertThat(heard.get().confidence()).isEqualTo(0.9);
        server.verify();
    }

    @Test
    void joinsEverythingHeardWhenAPauseSplitsTheClipIntoSeveralResults() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"results":[
                  {"alternatives":[{"transcript":"dua juta","confidence":0.8}]},
                  {"alternatives":[{"transcript":"lima ratus ribu","confidence":0.6}]}]}
                """, MediaType.APPLICATION_JSON));

        var heard = speech.transcribe(new byte[]{0, 0}, 16000);

        assertThat(heard.orElseThrow().text()).isEqualTo("dua juta lima ratus ribu");
        assertThat(heard.get().confidence()).isEqualTo(0.7, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void anAnswerWithNoResultsMeansNothingWasHeard() {
        // Google sends just billing metadata when the clip held no speech
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"totalBilledTime\":\"0s\"}", MediaType.APPLICATION_JSON));

        assertThat(speech.transcribe(new byte[]{0, 0}, 16000)).isEmpty();
    }

    @Test
    void googleRefusingTheCallIsReportedAsTheFeatureBeingUnavailable() {
        // 403 = API not enabled / account not allowed
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"API has not been used\"}}"));

        assertThatThrownBy(() -> speech.transcribe(new byte[]{0, 0}, 16000))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("belum tersedia");
    }

    @Test
    void missingCredentialsAreAlsoReportedAsUnavailable() {
        var noCredentials = new GoogleSpeechToText(RestClient.create(), URL, "latest_short",
                () -> { throw new IllegalStateException("No Google credentials"); });

        assertThatThrownBy(() -> noCredentials.transcribe(new byte[]{0, 0}, 16000))
                .isInstanceOf(ServiceUnavailableException.class);
    }
}
