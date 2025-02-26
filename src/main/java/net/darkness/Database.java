package net.darkness;

import com.j256.ormlite.dao.*;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.table.TableUtils;
import lombok.*;

import java.sql.SQLException;
import java.util.List;

import static net.darkness.Main.*;

public class Database {
    private static Dao<UserData, Long> userDao;

    public static void load()  {
        try (var source = new JdbcConnectionSource("jdbc:sqlite:users.db")) {
            userDao = DaoManager.createDao(source, UserData.class);
            TableUtils.createTableIfNotExists(source, UserData.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SneakyThrows(SQLException.class)
    public static UserData getUserData(long id) {
        return userDao.queryForId(id);
    }

    @SneakyThrows(SQLException.class)
    public static boolean userDataExists(long id) {
        return userDao.idExists(id);
    }

    @SneakyThrows(SQLException.class)
    public static UserData saveUserData(UserData data) {
        if (OWNER_IDS.contains(data.id))
            data.isManager = true; // Владелец бота всегда является менеджером

        userDao.createOrUpdate(data);
        return data;
    }

    @SneakyThrows(SQLException.class)
    public static List<UserData> getManagers() {
        return userDao.queryForEq("isManager", true);
    }

    @SneakyThrows(SQLException.class)
    public static List<UserData> getWaitingRequests() {
        return userDao.queryForEq("hasWaitingRequest", true);
    }

    @NoArgsConstructor(force = true)
    @RequiredArgsConstructor
    public static class UserData {
        @DatabaseField(id = true)
        public final long id;

        @DatabaseField
        public final String utmMark;

        @DatabaseField
        public final String username;

        @DatabaseField
        public boolean hasWaitingRequest;

        @DatabaseField
        public boolean isManager;

        @DatabaseField
        public long contactingWithID = -1;
    }
}
