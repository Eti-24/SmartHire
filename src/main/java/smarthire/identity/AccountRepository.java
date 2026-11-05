package smarthire.identity;

import java.util.Optional;

public interface AccountRepository {
    Optional<Account> findByEmail(String email);
    Optional<Account> findById(long id);
    Account create(String email, String passwordHash, Role role, String name);
    void updateProfile(long id, String name, String phone, String affiliation, String about);
}
