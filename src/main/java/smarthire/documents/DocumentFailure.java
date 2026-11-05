package smarthire.documents;

import smarthire.identity.UserError;

public final class DocumentFailure extends UserError {
    public enum Code { UNSUPPORTED, TOO_LARGE, MALFORMED, ENCRYPTED, NO_TEXT, STORAGE, INTERRUPTED, UNAVAILABLE, IN_USE }
    private final Code code;
    public DocumentFailure(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
}
