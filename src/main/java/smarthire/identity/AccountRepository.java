package smarthire.identity;

import java.util.Optional;
import java.util.List;

public interface AccountRepository {
    Optional<Account> findByEmail(String email);
    Optional<Account> findById(long id);
    Account create(String email, String passwordHash, Role role, String name);
    void updateProfile(long id, String name, String phone, String affiliation, String about);
    boolean changePassword(long id, long expectedVersion, String passwordHash);
    boolean replaceRecoveryCodes(long id, long expectedVersion, List<byte[]> hashes);
    boolean recoverPassword(String email, byte[] codeHash, String passwordHash);
}
