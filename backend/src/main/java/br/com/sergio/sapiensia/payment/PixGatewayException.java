package br.com.sergio.sapiensia.payment;

/** A failed call to the Pix provider. The message has only the step and a reason; never a body, key or CPF. */
public final class PixGatewayException extends RuntimeException {

    public enum Kind {
        DOCUMENT_REJECTED,
        REFUSED,
        UNAVAILABLE
    }

    private final Kind kind;
    private final String step;

    public PixGatewayException(Kind kind, String step, String reason) {
        super("Pix gateway failed: step=" + step + " reason=" + reason);
        this.kind = kind;
        this.step = step;
    }

    public Kind kind() {
        return kind;
    }

    public String step() {
        return step;
    }
}
