package se.lth.base.server.data;

import se.lth.base.server.database.DataAccess;
import se.lth.base.server.database.DataAccessException;
import se.lth.base.server.database.ErrorType;

import javax.xml.crypto.Data;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Basic functionality to support standard user operations. Some notable omissions are removing user, time out on
 * sessions, getting a user by name or id, etc.
 * <p>
 * This is intended to be as realistic as possible with reasonable security (single factor authentication).
 * The security measures are as follows.
 * <ul>
 * <li>All passwords are stored in a hashed format in the database, using @{@link Credentials#generatePasswordHash(long)}}</li>
 * <li>Usernames are used to salt passwords,
 * <a href="https://en.wikipedia.org/wiki/Salt_(cryptography)">see here for explanation.</a>
 * <li>When a user does login, it receives a UUID-token. This token is then used to authenticate,
 * using @{@link #getSession}.
 * </li>
 * </ul>
 *
 * @author Rasmus Ros, rasmus.ros@cs.lth.se
 * @see DataAccess
 */
public class UserDataAccess extends DataAccess<User> {

    public UserDataAccess(String driverUrl) {
        super(driverUrl, resultSet ->
                new User(resultSet.getInt("user_id"),
                        Role.valueOf(resultSet.getString("role")),
                        resultSet.getString("username")));
    }

    /**
     * Add a new user to the system.
     *
     * @param credentials of the new user, containing name, role, and password.
     * @throws DataAccessException if duplicated username or too short user names.
     */
    public User addUser(Credentials credentials) {
        long salt = Credentials.generateSalt();
        int userId = insert("INSERT INTO user (role_id, username, password_hash, salt) VALUES ((" +
                        "SELECT role_id FROM user_role WHERE user_role.role=?),?,?,?)",
                credentials.getRole().name(), credentials.getUsername(), credentials.generatePasswordHash(salt), salt);
        return new User(userId, credentials.getRole(), credentials.getUsername());
    }

    public User updateUser(int userId, Credentials credentials) {
        if (credentials.hasPassword()) {
            long salt = Credentials.generateSalt();
            execute("UPDATE user SET username = ?, password_hash = ?, salt = ?, role_id = (" +
                            "    SELECT user_role.role_id FROM user_role WHERE user_role.role = ?) " +
                            "WHERE user_id = ?",
                    credentials.getUsername(), credentials.generatePasswordHash(salt), salt,
                    credentials.getRole().name(), userId);
        } else {
            execute("UPDATE user SET username = ?, role_id = (" +
                            "    SELECT user_role.role_id FROM user_role WHERE user_role.role = ?) " +
                            "WHERE user_id = ?",
                    credentials.getUsername(), credentials.getRole().name(), userId);
        }
        return getUser(userId);
    }

    public User getUser(int userId) {
        return query("SELECT user_id, role, username FROM user " +
                "JOIN user_role ON user.role_id = user_role.role_id " +
                "WHERE user.user_id = ?", userId)
                .findFirst()
                .orElseThrow(() -> new DataAccessException("User not found", ErrorType.NOT_FOUND));
    }

    public boolean deleteUser(int userId) {
        return execute("DELETE FROM user WHERE user_id = ?", userId) > 0;
    }

    /**
     * @return all users in the system.
     */
    public List<User> getUsers() {
        return query("SELECT user_id, username, role FROM user " +
                "JOIN user_role ON user.role_id = user_role.role_id")
                .collect(Collectors.toList());
    }

    /**
     * Fetch session and the corresponding user.
     *
     * @param sessionId globally unqiue identifier, stored in the client.
     * @return session object wrapping the user.
     * @throws DataAccessException if the session is not found.
     */
    public Session getSession(UUID sessionId) {
        User user = queryFirst("SELECT user.user_id, username, role FROM user " +
                "JOIN user_role ON user_role.role_id = user.role_id " +
                "JOIN session ON session.user_id = user.user_id " +
                "WHERE session.session_uuid = ?", sessionId);
        execute("UPDATE session SET last_seen = CURRENT_TIMESTAMP() " +
                "WHERE session_uuid = ?", sessionId);
        return new Session(sessionId, user);
    }

    /**
     * Logout a user. This method is idempotent, meaning it is safe to repeat indefinitely.
     *
     * @param sessionId session to remove
     * @return true if the session was found, false otherwise.
     */
    public boolean removeSession(UUID sessionId) {
        return execute("DELETE FROM session WHERE session_uuid = ?", sessionId) > 0;
    }

    /**
     * Login a user.
     *
     * @param credentials username and plain text password.
     * @return New user session, consisting of a @{@link UUID} and @{@link User}.
     * @throws DataAccessException if the username or password does not match.
     */
    public Session authenticate(Credentials credentials) {
        Supplier<DataAccessException> onError = () ->
                new DataAccessException("Username or password incorrect", ErrorType.DATA_QUALITY);
        long salt = new DataAccess<>(getDriverUrl(), (rs) -> rs.getLong(1))
                .query("SELECT salt FROM user WHERE username = ?", credentials.getUsername())
                .findFirst()
                .orElseThrow(onError);
        String hash = credentials.generatePasswordHash(salt);
        User user = query("SELECT user_id, username, role FROM user " +
                "JOIN user_role ON user.role_id = user_role.role_id " +
                "WHERE username = ? AND password_hash = ?", credentials.getUsername(), hash)
                .findFirst()
                .orElseThrow(onError);
        UUID sessionId = insert("INSERT INTO session (user_id) " +
                "SELECT user_id from USER WHERE username = ?", user.getName());
        return new Session(sessionId, user);
    }
}