package org.Cry_Legende.sHProgress.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Progression des joueurs en MariaDB, partagée par tous les serveurs. Chargée seulement avec SHNetwork
 * (HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml).
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal et hors du fil du bus.</p>
 */
public final class ProgressDatabase {

    static final String PROGRESS = "shprogress_progress";
    static final String REWARDS = "shprogress_rewards";

    /** Gain en attente d'écriture : la somme ajoutée et la quantité qui termine le progrès (plafond). */
    public record Gain(int amount, int cap) {
    }

    /** Résultat d'une importation. */
    public record ImportResult(int players, int rows, int rewards) {
    }

    private final HikariDataSource pool;

    public ProgressDatabase(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHProgress");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(3);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : les lectures et écritures échouent alors une à une
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    /** Pour les tests : une source de données déjà configurée. */
    ProgressDatabase(HikariDataSource pool) {
        this.pool = pool;
    }

    /** Crée les tables si elles manquent. Des tables déjà là ne demandent aucun droit CREATE à l'utilisateur. */
    public void createTables() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            if (!exists(st, PROGRESS)) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + PROGRESS + " ("
                    + "uuid CHAR(36) NOT NULL, "
                    + "advancement VARCHAR(128) NOT NULL, "
                    + "progress INT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (uuid, advancement)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            }
            if (!exists(st, REWARDS)) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + REWARDS + " ("
                    + "uuid CHAR(36) NOT NULL, "
                    + "advancement VARCHAR(128) NOT NULL, "
                    + "claimed_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (uuid, advancement)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            }
        }
    }

    private static boolean exists(Statement st, String table) {
        try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + table + " LIMIT 1")) {
            return true;
        } catch (SQLException missing) {
            return false;
        }
    }

    /** Progression enregistrée du joueur : identifiant du progrès -> valeur (vide pour un nouveau joueur). */
    public Map<String, Integer> load(UUID uuid) throws SQLException {
        Map<String, Integer> progress = new HashMap<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT advancement, progress FROM " + PROGRESS + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    progress.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        return progress;
    }

    /**
     * Ajoute des gains à la progression du joueur. Chaque ajout est une seule instruction atomique plafonnée à la
     * quantité du progrès : ce qu'un autre serveur a écrit entre-temps n'est jamais écrasé.
     */
    public void add(UUID uuid, Map<String, Gain> gains) throws SQLException {
        if (gains.isEmpty()) {
            return;
        }
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + PROGRESS + " (uuid, advancement, progress) VALUES (?, ?, GREATEST(LEAST(?, ?), 0)) "
                + "ON DUPLICATE KEY UPDATE progress = GREATEST(LEAST(progress + ?, ?), 0)")) {
                for (Map.Entry<String, Gain> entry : gains.entrySet()) {
                    Gain gain = entry.getValue();
                    ps.setString(1, uuid.toString());
                    ps.setString(2, entry.getKey());
                    ps.setInt(3, gain.amount());
                    ps.setInt(4, gain.cap());
                    ps.setInt(5, gain.amount());
                    ps.setInt(6, gain.cap());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * Réserve la récompense d'un progrès pour le joueur. Vrai pour le seul serveur qui crée la ligne : c'est lui
     * qui la donne, tous les autres reçoivent faux.
     */
    public boolean claimReward(UUID uuid, String advancement, long now) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + REWARDS + " (uuid, advancement, claimed_at) VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, advancement);
            ps.setLong(3, now);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Reprend la progression des fichiers playerdata/ (joueur -> progrès -> valeur). Une progression déjà présente en
     * base n'est pas touchée, donc l'importation peut être relancée sans rien compter deux fois. Les progrès terminés
     * sont marqués comme déjà récompensés.
     *
     * @param completed vrai si la valeur termine le progrès (identifiant, valeur)
     */
    public ImportResult importProgress(Map<UUID, Map<String, Integer>> data, BiPredicate<String, Integer> completed, long now) throws SQLException {
        int players = 0;
        int rows = 0;
        int rewards = 0;
        try (Connection c = pool.getConnection();
             PreparedStatement progress = c.prepareStatement("INSERT IGNORE INTO " + PROGRESS + " (uuid, advancement, progress) VALUES (?, ?, ?)");
             PreparedStatement reward = c.prepareStatement("INSERT IGNORE INTO " + REWARDS + " (uuid, advancement, claimed_at) VALUES (?, ?, ?)")) {
            c.setAutoCommit(false);
            for (Map.Entry<UUID, Map<String, Integer>> player : data.entrySet()) {
                int before = rows;
                try {
                    for (Map.Entry<String, Integer> entry : player.getValue().entrySet()) {
                        if (entry.getValue() == null || entry.getValue() <= 0) {
                            continue;
                        }
                        progress.setString(1, player.getKey().toString());
                        progress.setString(2, entry.getKey());
                        progress.setInt(3, entry.getValue());
                        rows += progress.executeUpdate();
                        if (completed.test(entry.getKey(), entry.getValue())) {
                            reward.setString(1, player.getKey().toString());
                            reward.setString(2, entry.getKey());
                            reward.setLong(3, now);
                            rewards += reward.executeUpdate();
                        }
                    }
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                }
                if (rows > before) {
                    players++;
                }
            }
        }
        return new ImportResult(players, rows, rewards);
    }

    public void close() {
        pool.close();
    }
}
