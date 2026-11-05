package smarthire.identity;

public record Account(long id, String email, String passwordHash, Role role, boolean active,
                      String name, String phone, String affiliation, String about) {
}
