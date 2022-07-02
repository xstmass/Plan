package utilities;

import com.djrapitops.plan.gathering.domain.BaseUser;
import com.djrapitops.plan.gathering.domain.FinishedSession;
import com.djrapitops.plan.identification.Server;
import com.djrapitops.plan.identification.ServerUUID;
import com.djrapitops.plan.storage.database.DaggerDatabaseTestComponent;
import com.djrapitops.plan.storage.database.Database;
import com.djrapitops.plan.storage.database.DatabaseTestComponent;
import com.djrapitops.plan.storage.database.transactions.StoreServerInformationTransaction;
import com.djrapitops.plan.storage.database.transactions.events.PlayerServerRegisterTransaction;
import com.djrapitops.plan.storage.database.transactions.events.StoreJoinAddressTransaction;
import com.djrapitops.plan.storage.database.transactions.events.StoreSessionTransaction;
import com.djrapitops.plan.storage.database.transactions.events.WorldNameStoreTransaction;
import net.playeranalytics.plugin.scheduling.TimeAmount;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PerformanceTestDataGenerator {

    public static Path tempDir;
    private static Database database;

    public static void main(String[] args) throws Exception {
        tempDir = createTempDir();

        DatabaseTestComponent component = DaggerDatabaseTestComponent.builder()
                .bindTemporaryDirectory(tempDir)
                .build();

        DBPreparer dbPreparer = new DBPreparer(component, RandomData.randomNonPrivilegedPort());
        database = dbPreparer.prepareMySQL()
                .orElseThrow(() -> new IllegalStateException("Could not initialize database"));

        int numberOfServers = 1;
        int worldsPerServer = 1;
        int numberOfJoinAddresses = 50;
        int numberOfPlayers = 1000;
        int sessionsPerDay = 100;

        long timespan = TimeAmount.MONTH.toMillis(2);
        long earliestDate = System.currentTimeMillis() - timespan;

        long numberOfSessions = sessionsPerDay * TimeUnit.MILLISECONDS.toDays(timespan);
        long numberOfTPSEntries = TimeUnit.MILLISECONDS.toMinutes(timespan);
        long numberOfPingEntries = TimeUnit.MILLISECONDS.toMinutes(timespan) * sessionsPerDay / 20;

        List<ServerUUID> serverUUIDs = RandomData.pickMultiple(numberOfServers, ServerUUID::randomUUID);
        Map<ServerUUID, String[]> allWorlds = new HashMap<>();
        for (ServerUUID serverUUID : serverUUIDs) {
            Server server = RandomData.randomServer(serverUUID);
            database.executeTransaction(new StoreServerInformationTransaction(server));

            List<String> worlds = RandomData.pickMultiple(worldsPerServer, () -> RandomData.randomString(30));
            allWorlds.put(serverUUID, worlds.toArray(new String[0]));
            for (String world : worlds) {
                database.executeTransaction(new WorldNameStoreTransaction(serverUUID, world));
            }
        }

        List<String> joinAddresses = RandomData.pickMultiple(numberOfJoinAddresses, () -> RandomData.randomString(255));
        for (String joinAddress : joinAddresses) {
            database.executeTransaction(new StoreJoinAddressTransaction(joinAddress));
        }


        List<BaseUser> players = RandomData.pickMultiple(numberOfPlayers, () -> RandomData.randomBaseUser(earliestDate));
        Map<UUID, BaseUser> playerLookup = players.stream()
                .collect(Collectors.toMap(BaseUser::getUuid, Function.identity()));

        List<FinishedSession> sessions = RandomData.pickMultiple(numberOfSessions, () -> {
            ServerUUID server = RandomData.pickAtRandom(serverUUIDs);
            UUID[] playerUUIDs = RandomData.pickMultiple(RandomData.randomInt(1, 8), () -> RandomData.pickAtRandom(players))
                    .stream()
                    .map(BaseUser::getUuid)
                    .distinct()
                    .toArray(UUID[]::new);

            return RandomData.randomSession(
                    server,
                    allWorlds.get(server),
                    playerUUIDs
            );
        });

        Map<ServerUUID, Set<BaseUser>> playersToRegister = new HashMap<>();
        for (FinishedSession session : sessions) {
            UUID playerUUID = session.getPlayerUUID();
            ServerUUID serverUUID = session.getServerUUID();

            Set<BaseUser> usersOfServer = playersToRegister.computeIfAbsent(serverUUID, k -> new HashSet<>());
            usersOfServer.add(playerLookup.get(playerUUID));
        }

        for (var usersOfServer : playersToRegister.entrySet()) {
            ServerUUID serverUUID = usersOfServer.getKey();
            for (BaseUser baseUser : usersOfServer.getValue()) {
                database.executeTransaction(new PlayerServerRegisterTransaction(
                        baseUser.getUuid(),
                        baseUser::getRegistered,
                        baseUser.getName(),
                        serverUUID,
                        () -> RandomData.pickAtRandom(joinAddresses)));
            }
        }

        for (FinishedSession session : sessions) {
            database.executeTransaction(new StoreSessionTransaction(session));
        }
    }

    private static Path createTempDir() throws IOException {
        return Files.createTempDirectory("plan-performance-test-data-generator");
    }

}
