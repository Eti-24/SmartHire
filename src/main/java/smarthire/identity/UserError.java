package smarthire.identity;

/** An actionable message safe to display without exposing database or credential details. */
public class UserError extends RuntimeException {
    public UserError(String message) {
        super(message);
    }
}
