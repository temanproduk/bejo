package com.bejo.voice.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bejo.shared.exception.ServiceUnavailableException;
import com.bejo.shared.exception.TooManyRequestsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceModelsServiceTest {

    private static final String SHA = "a".repeat(64);
    private static final long HOUR_MS = 3_600_000L;
    private static final int LIMIT = 3;
    private static final String UNAVAILABLE = "Model suara belum tersedia";

    private final Map<String, String> objects = new ConcurrentHashMap<>();
    private final AtomicInteger signed = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();
    private final List<Long> ttlsSeen = new CopyOnWriteArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private RuntimeException readFails;
    private RuntimeException signFails;
    private int signOkBeforeFailing;          // the fake signs this many files, then starts throwing signFails
    private VoiceModelStorage storage;
    private Clock clock;
    private VoiceModelsService service;
    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(VoiceModelsService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);

        storage = new VoiceModelStorage() {
            @Override public Optional<byte[]> read(String objectName) {
                reads.incrementAndGet();
                if (readFails != null) throw readFails;
                return Optional.ofNullable(objects.get(objectName)).map(s -> s.getBytes(StandardCharsets.UTF_8));
            }
            @Override public String signedDownloadUrl(String objectName, Duration ttl) {
                if (signFails != null && signed.get() >= signOkBeforeFailing) throw signFails;
                signed.incrementAndGet();
                ttlsSeen.add(ttl.toSeconds());
                return "https://signed.test/" + objectName + "?ttl=" + ttl.toSeconds();
            }
        };
        clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        service = newService(3600, LIMIT, VoiceModelsService.MAX_TRACKED_USERS, clock);
    }

    @AfterEach
    void detachLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    private VoiceModelsService newService(long ttlSeconds, int limit, int maxTrackedUsers, Clock withClock) {
        return new VoiceModelsService(storage, new ObjectMapper(), withClock, ttlSeconds, limit, maxTrackedUsers);
    }

    private static String file(String name) {
        return "{\"name\":\"" + name + "\",\"bytes\":1234,\"sha256\":\"" + SHA + "\"}";
    }

    private static String manifestOf(String... fileJson) {
        return "{\"revision\":\"rev-1\",\"files\":[" + String.join(",", fileJson) + "]}";
    }

    private void upload(String manifestJson) {
        objects.put("converter/manifest.json", manifestJson);
    }

    private void uploadValid() {
        upload(manifestOf(file("convert.onnx"), file("encoder.onnx")));
    }

    private void assertUnavailable(String user) {
        assertThatThrownBy(() -> service.converterModels(user))
                .isInstanceOf(ServiceUnavailableException.class).hasMessage(UNAVAILABLE);
    }

    private List<ILoggingEvent> logged(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).collect(Collectors.toList());
    }

    private String loggedText() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    /** The user can still make exactly {@code LIMIT} good calls and is then refused: none of the failed calls above cost quota. */
    private void assertFullQuotaStillAvailable(String user) {
        for (int i = 0; i < LIMIT; i++) assertThat(service.converterModels(user).files()).isNotEmpty();
        assertThatThrownBy(() -> service.converterModels(user)).isInstanceOf(TooManyRequestsException.class);
    }

    // ---- the listing ----

    @Test
    void listsEveryFileWithASignedUrl() {
        uploadValid();

        var answer = service.converterModels("alice");

        assertThat(answer.revision()).isEqualTo("rev-1");
        assertThat(answer.files()).extracting("name").containsExactly("convert.onnx", "encoder.onnx");
        assertThat(answer.files().get(0).bytes()).isEqualTo(1234);
        assertThat(answer.files().get(0).sha256()).isEqualTo(SHA);
        assertThat(answer.files().get(0).url()).isEqualTo("https://signed.test/converter/convert.onnx?ttl=3600");
        assertThat(logs.list).as("a good call logs nothing").isEmpty();
    }

    @Test
    void theHashIsHandedOutLowerCase() {
        upload(manifestOf("{\"name\":\"convert.onnx\",\"bytes\":10,\"sha256\":\"" + "AB".repeat(32) + "\"}"));

        assertThat(service.converterModels("alice").files().get(0).sha256()).isEqualTo("ab".repeat(32));
    }

    // ---- the signed-url lifetime ----

    @Test
    void aConfiguredLifetimeReachesTheSigner() {
        service = newService(120, LIMIT, VoiceModelsService.MAX_TRACKED_USERS, clock);
        uploadValid();

        var answer = service.converterModels("alice");

        assertThat(ttlsSeen).containsExactly(120L, 120L);
        assertThat(answer.files().get(0).url()).endsWith("?ttl=120");
        assertThat(logs.list).isEmpty();
    }

    @Test
    void theLongestAndShortestAllowedLifetimesAreHonoured() {
        uploadValid();
        for (long edge : new long[] {604_800, 1}) {
            ttlsSeen.clear();
            service = newService(edge, LIMIT, VoiceModelsService.MAX_TRACKED_USERS, clock);

            service.converterModels("user" + edge);

            assertThat(ttlsSeen).as("signing uses " + edge).containsOnly(edge);
        }
        assertThat(logs.list).as("no warning for a lifetime that is allowed").isEmpty();
    }

    @Test
    void aLifetimeOutsideTheAllowedRangeFallsBackToAnHourWithAWarning() {
        for (long bad : new long[] {0, -60, 604_801}) {
            logs.list.clear();
            ttlsSeen.clear();
            service = newService(bad, LIMIT, VoiceModelsService.MAX_TRACKED_USERS, clock);   // must not throw: a bad optional setting is not a startup failure
            assertThat(logged(Level.WARN)).as("warned at construction for " + bad).hasSize(1);
            assertThat(logged(Level.WARN).get(0).getFormattedMessage()).contains(String.valueOf(bad)).contains("3600");

            uploadValid();
            service.converterModels("user" + bad);

            assertThat(ttlsSeen).as("signing uses the fallback for " + bad).containsOnly(3600L);
        }
    }

    // ---- 503s and what is logged ----

    @Test
    void aManifestThatWasNeverUploadedIs503AndOnlyAWarning() {
        assertUnavailable("alice");

        assertThat(logged(Level.WARN)).hasSize(1);
        assertThat(logged(Level.WARN).get(0).getFormattedMessage()).contains("converter/manifest.json").contains("not been uploaded");
        assertThat(logged(Level.ERROR)).isEmpty();
    }

    @Test
    void storageThatIsNotConfiguredIs503AndOnlyAWarning() {
        readFails = new IllegalStateException("bucket not set");   // what requireBucket throws: no cause

        assertUnavailable("alice");

        assertThat(logged(Level.WARN)).hasSize(1);
        assertThat(logged(Level.WARN).get(0).getFormattedMessage()).contains("not configured");
        assertThat(logged(Level.ERROR)).isEmpty();
    }

    @Test
    void credentialsThatFailToLoadAreAnErrorWithTheRootCauseAndTheStackTrace() {
        readFails = new IllegalStateException("Gagal memuat kredensial", new IOException("ADC file not found"));

        assertUnavailable("alice");                                   // the client still sees only the constant text

        assertThat(logged(Level.ERROR)).hasSize(1);
        ILoggingEvent error = logged(Level.ERROR).get(0);
        assertThat(error.getFormattedMessage()).contains("cannot read the manifest").contains("IOException: ADC file not found");
        assertThat(error.getThrowableProxy()).as("the stack trace is attached").isNotNull();
        assertThat(logged(Level.WARN)).as("not the expected not-configured state").isEmpty();
    }

    @Test
    void anyOtherStorageFailureOnReadIsAnErrorAndGivesTheSlotBack() {
        readFails = new RuntimeException("storage exploded");        // a plain RuntimeException, not an IllegalStateException

        for (int i = 0; i < LIMIT * 3; i++) assertUnavailable("alice");

        assertThat(logged(Level.ERROR)).hasSize(LIMIT * 3);
        assertThat(logged(Level.ERROR).get(0).getFormattedMessage()).contains("RuntimeException: storage exploded");
        assertThat(logged(Level.ERROR).get(0).getThrowableProxy()).isNotNull();
        readFails = null;
        uploadValid();
        assertFullQuotaStillAvailable("alice");
    }

    @Test
    void aSigningFailureLogsTheRootCauseNotJustTheWrapperAndNeverALink() {
        uploadValid();
        signFails = new RuntimeException("Failed to sign the provided bytes", new IOException("403 Forbidden: signBlob denied"));
        signOkBeforeFailing = 1;                                      // the first link is minted, the second file's signing fails

        assertUnavailable("alice");

        assertThat(logged(Level.ERROR)).hasSize(1);
        ILoggingEvent error = logged(Level.ERROR).get(0);
        assertThat(error.getFormattedMessage()).contains("cannot sign download links").contains("IOException: 403 Forbidden: signBlob denied");
        assertThat(error.getThrowableProxy()).isNotNull();
        assertThat(signed.get()).as("one link had been minted before the failure").isEqualTo(1);
        assertThat(loggedText()).as("no signed URL in the log").doesNotContain("signed.test");
        assertThat(error.getThrowableProxy().getMessage()).doesNotContain("signed.test");
    }

    @Test
    void aSigningFailureOfAnyKindIsAnErrorEvenWithoutACauseAndGivesTheSlotBack() {
        uploadValid();
        signFails = new IllegalStateException("not a service account");   // storage is configured by now: this is not the "not configured" state
        for (int i = 0; i < LIMIT * 3; i++) assertUnavailable("alice");
        assertThat(logged(Level.ERROR)).hasSize(LIMIT * 3);
        assertThat(logged(Level.WARN)).isEmpty();

        signFails = new RuntimeException("boom");                     // and a plain RuntimeException is the same
        for (int i = 0; i < LIMIT * 3; i++) assertUnavailable("alice");
        assertThat(logged(Level.ERROR)).hasSize(LIMIT * 6);

        signFails = null;
        assertFullQuotaStillAvailable("alice");
    }

    @Test
    void anInvalidManifestIsAnError() {
        upload(manifestOf(file("../x")));

        assertUnavailable("alice");

        assertThat(logged(Level.ERROR)).hasSize(1);
        assertThat(logged(Level.ERROR).get(0).getFormattedMessage()).contains("manifest is invalid");
    }

    @Test
    void invalidManifestsAreRefused() {
        List<String> bad = List.of(
                "not json at all",
                "[]",
                "{\"files\":[" + file("convert.onnx") + "]}",                                                     // no revision
                "{\"revision\":\"r\",\"files\":[]}",                                                              // no files
                "{\"revision\":\"r\"}",                                                                           // files missing
                manifestOf("{\"name\":\"convert.onnx\",\"bytes\":10,\"sha256\":\"abc\"}"),                         // short hash
                manifestOf("{\"name\":\"convert.onnx\",\"bytes\":0,\"sha256\":\"" + SHA + "\"}"),                  // zero size
                manifestOf("{\"name\":\"convert.onnx\",\"sha256\":\"" + SHA + "\"}"),                              // no size
                manifestOf(file("../secrets.txt")),
                manifestOf(file("sub/dir.onnx")),
                manifestOf(file(".hidden")),
                manifestOf(file("")));
        for (String manifest : bad) {
            upload(manifest);
            assertUnavailable("alice");
        }
        assertThat(signed.get()).as("nothing is signed for a manifest that is refused").isZero();
    }

    // ---- duplicate names and the size cap ----

    @Test
    void aManifestListingTheSameFileTwiceIsRefused() {
        upload(manifestOf(file("convert.onnx"), file("encoder.onnx"), file("convert.onnx")));

        assertUnavailable("alice");
        assertThat(signed.get()).isZero();
    }

    @Test
    void namesThatDifferOnlyInCaseCountAsTheSameFile() {
        upload(manifestOf(file("convert.onnx"), file("Convert.ONNX")));

        assertUnavailable("alice");
    }

    @Test
    void eightFilesAreAllowedButNineAreNot() {
        upload(manifestOfNFiles(8));
        assertThat(service.converterModels("alice").files()).hasSize(8);

        upload(manifestOfNFiles(9));
        assertUnavailable("alice");
    }

    private static String manifestOfNFiles(int n) {
        return manifestOf(IntStream.range(0, n).mapToObj(i -> file("part" + i + ".onnx")).toArray(String[]::new));
    }

    // ---- the per-user limit ----

    @Test
    void theCallAfterTheLimitIsRefusedAndSignsNothing() {
        uploadValid();
        for (int i = 0; i < LIMIT; i++) service.converterModels("alice");
        int signedSoFar = signed.get();

        assertThatThrownBy(() -> service.converterModels("alice"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Terlalu sering meminta model suara. Coba lagi nanti.");
        assertThat(signed.get()).as("a refused call mints no links").isEqualTo(signedSoFar);
    }

    @Test
    void anOverLimitCallerIsRefusedBeforeAnyStorageAccess() {
        uploadValid();
        for (int i = 0; i < LIMIT; i++) service.converterModels("alice");
        int readsSoFar = reads.get();

        assertThatThrownBy(() -> service.converterModels("alice")).isInstanceOf(TooManyRequestsException.class);

        assertThat(reads.get()).as("no bucket read for a refused call").isEqualTo(readsSoFar);
    }

    @Test
    void anotherUserIsNotAffectedByTheLimit() {
        uploadValid();
        for (int i = 0; i < LIMIT; i++) service.converterModels("alice");

        assertThat(service.converterModels("bob").files()).isNotEmpty();
    }

    @Test
    void theUserMayAskAgainOnceTheHourHasPassed() {
        uploadValid();
        for (int i = 0; i < LIMIT; i++) service.converterModels("alice");

        now.addAndGet(HOUR_MS);   // exactly an hour later the calls still count
        assertThatThrownBy(() -> service.converterModels("alice")).isInstanceOf(TooManyRequestsException.class);

        now.addAndGet(1);         // past the hour they do not
        assertThat(service.converterModels("alice").files()).isNotEmpty();
    }

    @Test
    void theHourIsRollingNotAFixedWindow() {
        uploadValid();
        service.converterModels("alice");             // t = 0
        now.addAndGet(30 * 60_000L);
        service.converterModels("alice");             // t = 30 min
        now.addAndGet(10 * 60_000L);
        service.converterModels("alice");             // t = 40 min: the limit is reached
        assertThatThrownBy(() -> service.converterModels("alice")).isInstanceOf(TooManyRequestsException.class);

        now.addAndGet(21 * 60_000L);                  // t = 61 min: only the first call has aged out
        service.converterModels("alice");
        assertThatThrownBy(() -> service.converterModels("alice")).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void eachUserHasTheirOwnCount() {
        uploadValid();
        List<String> users = IntStream.range(0, LIMIT * 2).mapToObj(i -> "user" + i).collect(Collectors.toList());

        for (String u : users) {
            for (int i = 0; i < LIMIT; i++) service.converterModels(u);
        }
        for (String u : users) {
            assertThatThrownBy(() -> service.converterModels(u)).isInstanceOf(TooManyRequestsException.class);
        }
    }

    // ---- a failed call must not use up the quota ----

    @Test
    void callsThatAnswer503BecauseNothingIsUploadedDoNotUseUpTheQuota() {
        for (int i = 0; i < LIMIT * 3; i++) assertUnavailable("alice");

        uploadValid();
        assertFullQuotaStillAvailable("alice");
    }

    @Test
    void callsThatMeetAnInvalidManifestDoNotUseUpTheQuota() {
        upload(manifestOf(file("../x")));
        for (int i = 0; i < LIMIT * 3; i++) assertUnavailable("alice");

        uploadValid();
        assertFullQuotaStillAvailable("alice");
    }

    @Test
    void callsThatCannotBeSignedDoNotUseUpTheQuota() {
        uploadValid();
        signFails = new IllegalStateException("cannot sign");
        for (int i = 0; i < LIMIT * 3; i++) assertUnavailable("alice");

        signFails = null;
        assertFullQuotaStillAvailable("alice");
    }

    // ---- who is tracked ----

    @Test
    void idleUsersArePrunedOnceTooManyAreTrackedButActiveOnesKeepTheirCount() {
        service = newService(3600, LIMIT, 5, clock);   // prune whenever more than 5 users are tracked
        uploadValid();
        for (int i = 0; i < 5; i++) service.converterModels("idle" + i);              // t = 0
        assertThat(service.trackedUsers()).isEqualTo(5);

        now.addAndGet(40 * 60_000L);                                                 // t = 40 min
        service.converterModels("busy");                                              // 6 tracked: prune pass, nobody is stale yet
        service.converterModels("busy");
        service.converterModels("busy");
        assertThat(service.trackedUsers()).isEqualTo(6);

        now.addAndGet(30 * 60_000L);                                                 // t = 70 min: the idle ones are over the hour
        service.converterModels("newcomer");                                          // prune pass

        assertThat(service.trackedUsers()).as("the five idle users were dropped").isEqualTo(2);
        assertThatThrownBy(() -> service.converterModels("busy")).as("an active user keeps their count")
                .isInstanceOf(TooManyRequestsException.class);
    }

    // ---- many calls at once ----

    @Test
    void aBurstOfParallelCallsFromOneUserGetsExactlyTheLimit() throws Exception {
        uploadValid();
        SlowClock slowClock = new SlowClock();
        service = newService(3600, 10, VoiceModelsService.MAX_TRACKED_USERS, slowClock);
        int threads = 20;

        for (int round = 0; round < 15; round++) {
            String user = "burst" + round;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    results.add(pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        try {
                            service.converterModels(user);
                            return true;
                        } catch (TooManyRequestsException refused) {
                            return false;
                        }
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                int succeeded = 0;
                for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) succeeded++;
                assertThat(succeeded).as("round " + round + ": exactly the limit succeeds").isEqualTo(10);
            } finally {
                pool.shutdownNow();
            }
        }
        assertThat(slowClock.overlapped).as("a user's time is read inside the lock, one caller at a time").isFalse();
    }

    /** A clock that takes a moment to read and notices if two callers are reading it at once. */
    private static final class SlowClock extends Clock {
        private final AtomicInteger inside = new AtomicInteger();
        volatile boolean overlapped;

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
        @Override public long millis() {
            if (inside.incrementAndGet() > 1) overlapped = true;
            LockSupport.parkNanos(100_000);
            inside.decrementAndGet();
            return 1_000_000_000L;
        }
    }
}
