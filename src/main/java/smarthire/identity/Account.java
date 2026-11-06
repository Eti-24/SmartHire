package smarthire.identity;

public record Account(long id, String email, String passwordHash, Role role, boolean active,
                      String name, String phone, String affiliation, String about, long credentialVersion) {
    public Account(long id, String email, String passwordHash, Role role, boolean active,
            String name, String phone, String affiliation, String about) {
        this(id, email, passwordHash, role, active, name, phone, affiliation, about, 1);
    }
}
