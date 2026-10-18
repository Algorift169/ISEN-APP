package org.jarvis.mobile.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class JarvisApiClient {
    public static final class ProviderException extends Exception {
        private final int statusCode;
        private final String errorCode;

        public ProviderException(int statusCode, String errorCode, String message) {
            super(message);
            this.statusCode = statusCode;
            this.errorCode = errorCode == null ? "" : errorCode;
        }

        public boolean isLimitReached() {
            String normalizedCode = errorCode.toLowerCase();
            String normalizedMessage = getMessage() == null ? "" : getMessage().toLowerCase();
            return statusCode == 429 || normalizedCode.contains("quota")
                    || normalizedCode.contains("rate_limit")
                    || normalizedMessage.contains("rate limit")
                    || normalizedMessage.contains("quota");
        }

    }

    public String complete(String provider, String apiKey, List<JSONObject> history,
                           String userMessage) throws Exception {
        boolean useGroq = ApiKeyStore.GROQ.equals(provider);
        String endpoint = useGroq
                ? "https://api.groq.com/openai/v1/chat/completions"
                : "https://api.openai.com/v1/chat/completions";
        String model = useGroq ? "openai/gpt-oss-20b" : "gpt-4o-mini";
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.7);
        body.put("max_tokens", 500);
        JSONArray messages = new JSONArray();
        for (JSONObject message : history) {
            messages.put(message);
        }
        messages.put(new JSONObject().put("role", "user").put("content", userMessage));
        body.put("messages", messages);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(45000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int responseCode = connection.getResponseCode();
            InputStream stream = responseCode >= 200 && responseCode < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readResponse(stream);
            JSONObject json = new JSONObject(response);
            if (responseCode < 200 || responseCode >= 300) {
                JSONObject error = json.optJSONObject("error");
                String message = error == null ? "The AI provider rejected the request."
                    : error.optString("message", "The AI provider rejected the request.");
                String code = error == null ? "" : error.optString("code", "");
                throw new ProviderException(responseCode, code, message);
            }
            return json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim();
        } finally {
            connection.disconnect();
        }
    }

    private String readResponse(InputStream stream) throws Exception {
        if (stream == null) {
            return "{}";
        }
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }
}