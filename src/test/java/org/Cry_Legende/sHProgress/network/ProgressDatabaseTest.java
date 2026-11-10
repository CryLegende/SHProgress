package org.Cry_Legende.sHProgress.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.Cry_Legende.sHProgress.network.ProgressDatabase.Gain;
import org.Cry_Legende.sHProgress.network.ProgressDatabase.ImportResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requêtes de la progression sur un vrai MariaDB : gains de deux serveurs qui s'additionnent, récompense donnée
 * une seule fois, importation relançable. Ignoré sans SHPROGRESS_TEST_URL (une base d'essai dont le nom contient
 * « test », par exemple jdbc:mariadb://127.0.0.1:3399/zz_shprogress_test), SHPROGRESS_TEST_USER et SHPROGRESS_TEST_PASSWORD.
 */
class ProgressDatabaseTest {

    private HikariDataSource pool;
    private ProgressDatabase db;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("SHPROGRESS_TEST_URL");
        Assumptions.assumeTrue(url != null && url.contains("test"), "SHPROGRESS_TEST_URL absent ou sans « test » : test MariaDB ignoré");
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url);
        hikari.setUsername(System.getenv().getOrDefault("SHPROGRESS_TEST_USER", "shprogress"));
        hikari.setPassword(System.getenv().getOrDefault("SHPROGRESS_TEST_PASSWORD", "shprogress"));
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.setMaximumPoolSize(24);
        pool = new HikariDataSource(hikari);
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS " + ProgressDatabase.PROGRESS);
            st.executeUpdate("DROP TABLE IF EXISTS " + ProgressDatabase.REWARDS);
        }
        db = new ProgressDatabase(pool);
        db.createTables();
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    private static void parallel(int threads, ThrowingRunnable task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                task.run();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void gainsFromTwoServersAddUp() throws Exception {
        UUID player = UUID.randomUUID();
        // deux serveurs écrivent chacun 10 fois +1 sur le même progrès : aucun gain n'est écrasé
        parallel(2, () -> {
            for (int i = 0; i < 10; i++) {
                db.add(player, Map.of("tree/charbon", new Gain(1, 500)));
            }
        });
        assertEquals(20, db.load(player).get("tree/charbon"));
    }

    @Test
    void gainsStopAtTheCapAndAtZero() throws Exception {
        UUID player = UUID.randomUUID();
        db.add(player, Map.of("tree/a", new Gain(8, 10)));
        db.add(player, Map.of("tree/a", new Gain(8, 10)));
        assertEquals(10, db.load(player).get("tree/a"));
        db.add(player, Map.of("tree/a", new Gain(-50, 10)));
        assertEquals(0, db.load(player).get("tree/a"));
        // première écriture plafonnée aussi
        db.add(player, Map.of("tree/b", new Gain(99, 10)));
        assertEquals(10, db.load(player).get("tree/b"));
    }

    @Test
    void rewardIsClaimedByOneServerOnly() throws Exception {
        UUID player = UUID.randomUUID();
        List<Boolean> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        parallel(16, () -> results.add(db.claimReward(player, "tree/charbon", System.currentTimeMillis())));
        assertEquals(16, results.size());
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        // un autre progrès du même joueur reste réservable
        assertTrue(db.claimReward(player, "tree/fer", System.currentTimeMillis()));
        assertFalse(db.claimReward(player, "tree/fer", System.currentTimeMillis()));
    }

    @Test
    void importCanBeRerunWithoutCountingTwice() throws Exception {
        UUID player = UUID.randomUUID();
        Map<UUID, Map<String, Integer>> data = Map.of(player, Map.of("tree/a", 5, "tree/b", 10, "tree/c", 0));
        ImportResult first = db.importProgress(data, (id, value) -> value >= 10, 1L);
        assertEquals(1, first.players());
        assertEquals(2, first.rows());
        assertEquals(1, first.rewards());
        // la progression a avancé depuis : l'importation relancée n'y touche pas
        db.add(player, Map.of("tree/a", new Gain(3, 10)));
        ImportResult second = db.importProgress(data, (id, value) -> value >= 10, 2L);
        assertEquals(0, second.rows());
        assertEquals(0, second.rewards());
        assertEquals(8, db.load(player).get("tree/a"));
        assertFalse(db.claimReward(player, "tree/b", 3L));
    }
}
