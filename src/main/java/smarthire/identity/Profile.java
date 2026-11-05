package smarthire.identity;

public record Profile(long id, String email, Role role, String name, String phone,
                      String affiliation, String about) {
    public static Profile from(Account account) {
        return new Profile(account.id(), account.email(), account.role(), account.name(),
                account.phone(), account.affiliation(), account.about());
    }
}
