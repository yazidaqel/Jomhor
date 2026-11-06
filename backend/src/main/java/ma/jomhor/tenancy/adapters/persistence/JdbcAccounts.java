package ma.jomhor.tenancy.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.tenancy.application.Accounts;
import ma.jomhor.tenancy.domain.Membership;
import ma.jomhor.tenancy.domain.StaffRole;
import ma.jomhor.tenancy.domain.User;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccounts implements Accounts {

    private static final String USER_COLUMNS = "id, phone_e164, display_name, locale";

    private final JdbcClient jdbc;

    JdbcAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public User upsertByPhone(String phone) {
        jdbc.sql("insert into users (phone_e164) values (?) on conflict (phone_e164) do nothing").param(phone).update();
        return jdbc.sql("select " + USER_COLUMNS + " from users where phone_e164 = ?")
                .param(phone).query(JdbcAccounts::user).single();
    }

    @Override
    public Optional<User> findUser(UUID userId) {
        return jdbc.sql("select " + USER_COLUMNS + " from users where id = ?")
                .param(userId).query(JdbcAccounts::user).optional();
    }

    @Override
    public Optional<User> updateProfile(UUID userId, String displayName, String locale) {
        return jdbc.sql("""
                        update users set display_name = case when cast(:name as text) is null then display_name
                                                             else nullif(:name, '') end,
                                         locale = coalesce(cast(:locale as text), locale)
                        where id = :id
                        returning\s""" + USER_COLUMNS)
                .param("name", displayName).param("locale", locale).param("id", userId)
                .query(JdbcAccounts::user).optional();
    }

    @Override
    public Optional<UUID> findUserIdByPhone(String phone) {
        return jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).optional();
    }

    @Override
    public boolean hasActiveStaff(String phone) {
        return jdbc.sql("""
                        select exists (select 1 from staff s join users u on u.id = s.user_id
                                       where u.phone_e164 = ? and s.active)
                        """)
                .param(phone).query(Boolean.class).single();
    }

    // Cross-organizer on purpose: lists one user's own memberships, keyed by user_id.
    @Override
    public List<Membership> activeMemberships(UUID userId) {
        return jdbc.sql("""
                        select s.organizer_id, o.name, s.role::text as role
                        from staff s join organizers o on o.id = s.organizer_id
                        where s.user_id = ? and s.active
                        order by o.name
                        """)
                .param(userId)
                .query((rs, n) -> new Membership(rs.getObject("organizer_id", UUID.class), rs.getString("name"),
                        StaffRole.valueOf(rs.getString("role"))))
                .list();
    }

    @Override
    public Optional<StaffRole> activeRole(UUID organizerId, UUID userId) {
        return jdbc.sql("select role::text from staff where organizer_id = ? and user_id = ? and active")
                .params(organizerId, userId)
                .query(String.class).optional()
                .map(StaffRole::valueOf);
    }

    private static User user(ResultSet rs, int n) throws SQLException {
        return new User(rs.getObject("id", UUID.class), rs.getString("phone_e164"),
                rs.getString("display_name"), rs.getString("locale"));
    }
}
