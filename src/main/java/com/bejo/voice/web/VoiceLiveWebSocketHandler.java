package com.bejo.voice.web;

import com.bejo.shared.exception.ServiceUnavailableException;
import com.bejo.shared.exception.TooManyRequestsException;
import com.bejo.voice.dto.VoiceLiveEventDto;
import com.bejo.voice.dto.VoiceLiveSaleDto;
import com.bejo.voice.dto.VoiceLiveStartDto;
import com.bejo.voice.dto.VoiceTransactionResponseDto;
import com.bejo.voice.service.LiveSale;
import com.bejo.voice.service.VoiceLiveService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Principal;
import java.util.Set;

/**
 * The live "say the sale" connection ({@link VoiceLiveWebSocketConfig#PATH}). Thin, like a controller: it turns frames into calls on
 * a {@link LiveSale} and the sale's reports into frames, and nothing else.
 *
 * <p>The app sends a text frame {@code {"type":"start","products":[...]}}, then binary frames of 16 kHz mono 16-bit PCM as it records,
 * then {@code {"type":"end"}}. It receives {@code ready}, any number of {@code draft}, then {@code done} — or {@code error} at any
 * point, after which it should send the recording the one-shot way. Who the user is comes from the upgrade request, which the
 * API-token and JWT filters have already vetted.
 */
@Component
public class VoiceLiveWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceLiveWebSocketHandler.class);

    private static final String SALE = "voice.live.sale";
    /** A client that cannot take what it is sent for this long is dropped, rather than holding the session's threads. */
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final VoiceLiveService live;
    private final ObjectMapper json;
    private final Validator validator;

    public VoiceLiveWebSocketHandler(VoiceLiveService live, ObjectMapper json, Validator validator) {
        this.live = live;
        this.json = json;
        this.validator = validator;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws IOException {
        Principal user = raw.getPrincipal();
        if (user == null) {   // the filters let nobody anonymous this far; refuse rather than assume
            raw.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        // Frames to the app come from the model's threads and the timers as well as from here, and a session allows one sender at a time
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        FrameSink sink = new FrameSink(session, json);
        try {
            raw.getAttributes().put(SALE, live.begin(user.getName(), sink));
        } catch (TooManyRequestsException e) {
            sink.error("rate_limited", e.getMessage());
            sink.close();
        } catch (ServiceUnavailableException e) {
            sink.error("unavailable", e.getMessage());
            sink.close();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        LiveSale sale = sale(session);
        if (sale == null) return;
        JsonNode frame;
        try {
            frame = json.readTree(message.getPayload());
        } catch (JsonProcessingException e) {
            sale.reject("Pesan bukan JSON yang valid");
            return;
        }
        switch (frame.path("type").asText("")) {
            case "start" -> {
                VoiceLiveStartDto start;
                try {
                    start = json.treeToValue(frame, VoiceLiveStartDto.class);
                } catch (JsonProcessingException e) {
                    sale.reject("Pesan start tidak valid");
                    return;
                }
                Set<ConstraintViolation<VoiceLiveStartDto>> problems = validator.validate(start);
                if (!problems.isEmpty()) {
                    sale.reject(problems.iterator().next().getMessage());
                    return;
                }
                sale.start(start.products());
            }
            case "end" -> sale.end();
            default -> sale.reject("Jenis pesan tidak dikenal");
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        LiveSale sale = sale(session);
        if (sale == null) return;
        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);
        sale.audio(pcm);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        log.debug("[VOICE] live transport error: {}", error.getClass().getSimpleName());
        LiveSale sale = sale(session);
        if (sale != null) sale.clientGone();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        LiveSale sale = sale(session);
        if (sale != null) sale.clientGone();
    }

    private static LiveSale sale(WebSocketSession session) {
        return (LiveSale) session.getAttributes().get(SALE);
    }

    /** The sale's reports, as frames. A frame that cannot be sent (the app is gone) is dropped; the sale hears about it from the close. */
    private record FrameSink(WebSocketSession session, ObjectMapper json) implements LiveSale.Sink {

        @Override
        public void ready() {
            send(VoiceLiveEventDto.ready());
        }

        @Override
        public void draft(VoiceTransactionResponseDto sale) {
            send(VoiceLiveSaleDto.of("draft", sale));
        }

        @Override
        public void done(VoiceTransactionResponseDto sale) {
            send(VoiceLiveSaleDto.of("done", sale));
        }

        @Override
        public void error(String code, String message) {
            send(VoiceLiveEventDto.error(code, message));
        }

        @Override
        public void close() {
            try {
                session.close(CloseStatus.NORMAL);
            } catch (IOException | RuntimeException e) {
                log.debug("[VOICE] live close failed: {}", e.getClass().getSimpleName());
            }
        }

        private void send(Object frame) {
            try {
                session.sendMessage(new TextMessage(json.writeValueAsString(frame)));
            } catch (IOException | RuntimeException e) {
                log.debug("[VOICE] live frame not sent: {}", e.getClass().getSimpleName());
            }
        }
    }
}
