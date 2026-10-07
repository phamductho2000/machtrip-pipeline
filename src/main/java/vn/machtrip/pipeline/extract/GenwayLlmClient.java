package vn.machtrip.pipeline.extract;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for the Genway gateway, implemented strictly from docs/genway-llm-api.md: {@code POST /api/generation} with
 * the envelope {@code {model_key, call_type: "text", input: {messages, max_tokens}}}, {@code Authorization: Bearer},
 * and an {@code Idempotency-Key} header.
 *
 * <p>What the doc says about errors: a rejected request comes back with an HTTP status (400/401/403/404/500), and a
 * provider failure that Genway already retried comes back as HTTP 200 with {@code success:false} and an
 * {@code errorCode}. Both are final for the video (never retried here). Only connection errors and timeouts of our own
 * HTTP call are retried (see {@link LlmCallPolicy}), which is safe because of the Idempotency-Key.
 *
 * <p>Not covered by the doc (so decided here and to be confirmed by the first real call): where a system prompt goes
 * (see {@code genway.system-prompt-as}) and the exact shape of {@code data.response} for text models. The doc only says
 * it is "the content returned directly by the model"; this client recognises a plain string, an OpenAI-style chat
 * completion and an Anthropic-style message, and fails with a clear error for anything else.
 */
@Component
public class GenwayLlmClient implements LlmClient {

    private final GenwayProperties props;
    private final ObjectMapper mapper;
    private final LlmCallPolicy policy;
    private volatile RestClient client; // built on first use, so dry runs and offline commands never create it

    public GenwayLlmClient(GenwayProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.policy = new LlmCallPolicy(props.maxConnectRetries(), props.initialBackoff());
    }

    @Override
    public LlmResult complete(LlmRequest request) {
        if (props.baseUrl().isBlank() || props.apiKey().isBlank()) {
            throw new LlmException("GENWAY_BASE_URL and GENWAY_API_KEY must both be set to call Genway", true);
        }
        ObjectNode body = body(request);
        byte[] response = policy.execute(() -> client().post().uri("/api/generation")
                .headers(h -> {
                    h.set(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey());
                    if (!props.kongApiKey().isBlank()) {
                        h.set("apikey", props.kongApiKey());
                    }
                })
                .header("Idempotency-Key", request.idempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw httpError(res.getStatusCode().value(), res.getBody().readAllBytes());
                })
                .body(byte[].class));
        return parse(response, request);
    }

    /** The doc's text envelope. The JSON schema of the request is not sent: the doc describes no such field. */
    ObjectNode body(LlmRequest r) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model_key", r.model());
        body.put("call_type", "text");
        ObjectNode input = body.putObject("input");
        ArrayNode messages = input.putArray("messages");
        if ("field".equals(props.systemPromptAs())) {
            input.put("system", r.system());
        } else {
            messages.addObject().put("role", "system").put("content", r.system());
        }
        messages.addObject().put("role", "user").put("content", r.user());
        input.put("max_tokens", r.maxOutputTokens());
        if (!props.thinking().isBlank()) {
            input.putObject("thinking").put("type", props.thinking());
        }
        return body;
    }

    private LlmResult parse(byte[] bytes, LlmRequest request) {
        JsonNode root;
        try {
            root = mapper.readTree(bytes);
        } catch (IOException e) {
            throw new LlmException("Genway returned a body that is not JSON");
        }
        if (!root.path("success").asBoolean(false)) {
            String code = root.path("errorCode").asText("an error");
            throw new LlmException("Genway reported " + code + ": " + root.path("message").asText("(no message)")
                    + requestId(root), "PROVIDER_BILLING_ERROR".equals(code) || "UNAUTHORIZED".equals(code)
                    || "INVALID_PARAMS".equals(code));
        }
        if ("processing".equals(root.path("status").asText())) {
            throw new LlmException("Genway answered status 'processing' (a queue-based model); extraction supports "
                    + "only synchronous text models" + requestId(root));
        }
        JsonNode response = root.path("data").path("response");
        String text = textOf(response);
        if (text == null) {
            throw new LlmException("Genway data.response has an unrecognised shape (expected a string, an "
                    + "OpenAI-style chat completion or an Anthropic-style message). Shape (no values): "
                    + shapeOf(response) + requestId(root));
        }
        JsonNode usage = response.path("usage");
        return new LlmResult(text, tokens(usage, "prompt_tokens", "input_tokens"),
                tokens(usage, "completion_tokens", "output_tokens"), response.path("model").asText(request.model()));
    }

    /**
     * Values-free description of a response, for error messages: key names and JSON types only, plus the block types
     * of a {@code content} array (e.g. {@code thinking}, {@code text}). Never includes any text of the response.
     */
    static String shapeOf(JsonNode node) {
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            node.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                String t = v.getNodeType().toString().toLowerCase();
                if (v.isArray() && "content".equals(e.getKey())) {
                    List<String> types = new ArrayList<>();
                    v.forEach(b -> types.add(b.path("type").asText(b.getNodeType().toString().toLowerCase())));
                    t = "array" + types;
                }
                keys.add(e.getKey() + ":" + t);
            });
            return "{" + String.join(", ", keys) + "}";
        }
        return node.getNodeType().toString().toLowerCase();
    }

    private static String textOf(JsonNode response) {
        if (response.isTextual()) {
            return response.asText();
        }
        JsonNode chat = response.path("choices").path(0).path("message").path("content");
        if (chat.isTextual()) {
            return chat.asText();
        }
        if (response.path("content").isArray()) {
            List<String> parts = new ArrayList<>();
            response.path("content").forEach(block -> {
                if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
                    parts.add(block.path("text").asText());
                }
            });
            return parts.isEmpty() ? null : String.join("", parts);
        }
        return null;
    }

    private static Integer tokens(JsonNode usage, String... names) {
        for (String n : names) {
            if (usage.path(n).isIntegralNumber()) {
                return usage.path(n).asInt();
            }
        }
        return null;
    }

    private LlmException httpError(int status, byte[] body) {
        String code = null;
        String message = null;
        try {
            JsonNode n = mapper.readTree(body);
            code = n.path("errorCode").asText(null);
            message = n.path("message").asText(null);
        } catch (IOException ignored) {
            // not JSON: the status alone has to do
        }
        String hint = switch (status) {
            case 401 -> " (missing, wrong or revoked GENWAY_API_KEY)";
            case 403 -> " (the tenant is not granted the provider of this model: ask the Genway admin)";
            case 400, 404 -> " (invalid request: unknown or inactive model_key, or call_type/input mismatch)";
            default -> "";
        };
        return new LlmException("Genway answered HTTP " + status + (code == null ? "" : " " + code)
                + (message == null ? "" : ": " + message) + hint + " (not retried)",
                status == 401 || status == 403 || status == 400 || status == 404);
    }

    private static String requestId(JsonNode root) {
        return root.hasNonNull("requestId") ? " [requestId " + root.get("requestId").asText() + "]" : "";
    }

    private RestClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1).connectTimeout(props.connectTimeout()).build());
                    factory.setReadTimeout(props.readTimeout());
                    client = RestClient.builder().baseUrl(props.baseUrl().replaceAll("/+$", ""))
                            .requestFactory(factory).build();
                }
            }
        }
        return client;
    }
}
