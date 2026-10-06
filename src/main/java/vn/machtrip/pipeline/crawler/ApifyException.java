package vn.machtrip.pipeline.crawler;

/** Apify call failure. {@code type} is Apify's error.type (or null for transport errors). */
public class ApifyException extends RuntimeException {

    private final int httpStatus;
    private final String type;

    public ApifyException(int httpStatus, String type, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.type = type;
    }

    public ApifyException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = -1;
        this.type = null;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String type() {
        return type;
    }

    /** 429, 5xx and transport errors are retryable; every other 4xx is not. */
    public boolean isRetryable() {
        return httpStatus == -1 || httpStatus == 429 || httpStatus >= 500;
    }

    /** Builds the exception for an Apify error response, with a clear message for the known error types. */
    public static ApifyException fromResponse(int httpStatus, String type, String apifyMessage) {
        String detail = apifyMessage == null || apifyMessage.isBlank() ? "" : " Apify said: " + apifyMessage;
        String msg = switch (type == null ? "" : type) {
            case "invalid-input" -> "Apify rejected the actor input (invalid-input). Check config/actors/*.json "
                    + "or run `validate-input`." + detail;
            case "invalid-token", "token-not-provided" -> "Apify token is missing or invalid (" + type
                    + "). Check APIFY_TOKEN." + detail;
            case "rate-limit-exceeded" -> "Apify rate limit exceeded (rate-limit-exceeded); gave up after retrying."
                    + detail;
            case "concurrent-runs-limit-exceeded" -> "Too many concurrent Apify runs for this account "
                    + "(concurrent-runs-limit-exceeded). Wait for running runs to finish." + detail;
            case "max-total-charge-usd-below-minimum" -> "maxTotalChargeUsd is below the minimum charge of this actor "
                    + "(max-total-charge-usd-below-minimum). Raise pipeline.apify.max-total-charge-usd." + detail;
            default -> "Apify request failed: HTTP " + httpStatus + (type == null ? "" : " (" + type + ")") + "."
                    + detail;
        };
        return new ApifyException(httpStatus, type, msg);
    }
}
