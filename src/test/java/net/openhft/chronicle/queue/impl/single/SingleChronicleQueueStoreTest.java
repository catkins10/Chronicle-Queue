package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.core.util.ThrowingConsumer;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class SingleChronicleQueueStoreTest {
    private static final int INDEX_SPACING = 4;
    private static final int RECORD_COUNT = INDEX_SPACING * 10;
    private static final RollCycles ROLL_CYCLE = RollCycles.DAILY;
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private static void assertExcerptsAreIndexed(final SingleChronicleQueue queue, final long[] indices,
                                                 final Function<Integer, Boolean> shouldBeIndexed, final ScanResult expectedScanResult) throws Exception {
        final Field field = SingleChronicleQueueStore.class.getDeclaredField("recovery");
        field.setAccessible(true);
        final SingleChronicleQueueStore wireStore = (SingleChronicleQueueStore)
                queue.storeForCycle(queue.cycle(), 0L, true);
        final TimedStoreRecovery recovery = (TimedStoreRecovery) field.get(wireStore);
        final SCQIndexing indexing = wireStore.indexing;
        for (int i = 0; i < RECORD_COUNT; i++) {
            final int startLinearScanCount = indexing.linearScanCount;
            final ScanResult scanResult = indexing.moveToIndex(recovery, (SingleChronicleQueueExcerpts.StoreTailer) queue.createTailer(), indices[i]);
            assertThat(scanResult, is(expectedScanResult));

            if (shouldBeIndexed.apply(i)) {
                assertThat(indexing.linearScanCount, is(startLinearScanCount));
            } else {
                assertThat(indexing.linearScanCount, is(startLinearScanCount + 1));
            }
        }
    }

    private static long[] writeMessagesStoreIndices(final ExcerptAppender appender, final ExcerptTailer tailer) {
        final long[] indices = new long[RECORD_COUNT];
        for (int i = 0; i < RECORD_COUNT; i++) {
            try (final DocumentContext ctx = appender.writingDocument()) {
                ctx.wire().getValueOut().int32(i);
            }
        }

        for (int i = 0; i < RECORD_COUNT; i++) {
            try (final DocumentContext ctx = tailer.readingDocument()) {
                assertThat("Expected record at index " + i, ctx.isPresent(), is(true));
                indices[i] = tailer.index();
            }
        }
        return indices;
    }

    @Test
    public void shouldNotPerformIndexingOnAppendWhenLazyIndexingIsEnabled() throws Exception {
        runTest(queue -> {
            final ExcerptAppender appender = queue.acquireAppender();
            appender.lazyIndexing(true);
            final long[] indices = writeMessagesStoreIndices(appender, queue.createTailer());
            assertExcerptsAreIndexed(queue, indices, i -> false, ScanResult.NOT_REACHED);
        });
    }

    @Test
    public void shouldPerformIndexingOnRead() throws Exception {
        runTest(queue -> {
            final ExcerptAppender appender = queue.acquireAppender();
            appender.lazyIndexing(true);
            final long[] indices = writeMessagesStoreIndices(appender, queue.createTailer().indexing(true));
            assertExcerptsAreIndexed(queue, indices, i -> i % INDEX_SPACING == 0, ScanResult.FOUND);
        });
    }

    @Test
    public void shouldPerformIndexingOnAppend() throws Exception {
        runTest(queue -> {
            final ExcerptAppender appender = queue.acquireAppender();
            appender.lazyIndexing(false);
            final long[] indices = writeMessagesStoreIndices(appender, queue.createTailer());
            assertExcerptsAreIndexed(queue, indices, i -> i % INDEX_SPACING == 0, ScanResult.FOUND);
        });
    }

    private void runTest(final ThrowingConsumer<SingleChronicleQueue, Exception> testMethod) throws Exception {
        try (final SingleChronicleQueue queue = SingleChronicleQueueBuilder.binary(tmpDir.newFolder()).
                testBlockSize().timeProvider(clock::get).
                rollCycle(ROLL_CYCLE).indexSpacing(INDEX_SPACING).
                build()) {
            testMethod.accept(queue);
        }
    }
}