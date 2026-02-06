package io.nebuliton.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nebuliton.config.Config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public final class OpenAIClient {
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;

    public OpenAIClient(Config.OpenAI config) {
        this.apiKey = config.apiKey;
        this.baseUrl = config.baseUrl;
        this.timeout = Duration.ofSeconds(config.timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    public String createChatCompletion(String model, List<ChatMessage> messages, double temperature, int maxTokens)
            throws IOException, InterruptedException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", model);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);

        ArrayNode messageArray = payload.putArray("messages");
        for (ChatMessage message : messages) {
            ObjectNode node = messageArray.addObject();
            node.put("role", message.role());
            node.put("content", message.content());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveEndpoint("/chat/completions")))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("OpenAI API error: " + response.statusCode() + " " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode()) {
            throw new IOException("OpenAI API returned no message content");
        }

        return content.asText().trim();
    }

    private String resolveEndpoint(String path) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }
        return baseUrl + path;
    }

    public record ChatMessage(String role, String content) {
    }
}
