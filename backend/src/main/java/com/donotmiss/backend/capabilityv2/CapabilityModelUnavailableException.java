package com.donotmiss.backend.capabilityv2;

/**
 * The model gave no usable answer, so the pipeline must not pretend it decided anything.
 *
 * <p>Before Stage 17 an outage silently became "no labels", the message was acknowledged and the
 * completion lost its score forever. Throwing instead lets the listener defer the whole message.
 * {@link #retryable()} is false when the model is switched off by configuration, because
 * retrying cannot help there.</p>
 */
public class CapabilityModelUnavailableException extends RuntimeException {
    private final String stage;
    private final boolean retryable;

    private CapabilityModelUnavailableException(String stage, boolean retryable, String message) {
        super(message);
        this.stage = stage;
        this.retryable = retryable;
    }

    public static CapabilityModelUnavailableException disabled(String stage) {
        return new CapabilityModelUnavailableException(stage, false, stage + "：模型未启用，无法完成归因");
    }

    public static CapabilityModelUnavailableException noResponse(String stage) {
        return new CapabilityModelUnavailableException(stage, true, stage + "：模型超时、报错或返回了无法解析的内容");
    }

    public String stage() {
        return stage;
    }

    public boolean retryable() {
        return retryable;
    }
}
