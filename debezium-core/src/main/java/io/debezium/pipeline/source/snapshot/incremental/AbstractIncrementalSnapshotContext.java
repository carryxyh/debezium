/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline.source.snapshot.incremental;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.annotation.NotThreadSafe;
import io.debezium.relational.TableId;
import io.debezium.util.HexConverter;

/**
 * A class describing current state of incremental snapshot
 *
 * @author Jiri Pechanec
 *
 */
@NotThreadSafe
public class AbstractIncrementalSnapshotContext<T> implements IncrementalSnapshotContext<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractIncrementalSnapshotContext.class);

    // TODO Consider which (if any) information should be exposed in source info
    public static final String INCREMENTAL_SNAPSHOT_KEY = "incremental_snapshot";
    public static final String DATA_COLLECTIONS_TO_SNAPSHOT_KEY = INCREMENTAL_SNAPSHOT_KEY + "_collections";
    public static final String EVENT_PRIMARY_KEY = INCREMENTAL_SNAPSHOT_KEY + "_primary_key";
    public static final String TABLE_MAXIMUM_KEY = INCREMENTAL_SNAPSHOT_KEY + "_maximum_key";

    /**
     * @code(true) if window is opened and deduplication should be executed
     */
    protected boolean windowOpened = false;

    /**
     * The last primary key in chunk that is now in process.
     */
    private Object[] chunkEndPosition;

    // TODO After extracting add into source info optional block
    // incrementalSnapshotWindow{String from, String to}
    // State to be stored and recovered from offsets
    private final Queue<T> dataCollectionsToSnapshot = new LinkedList<>();

    private final boolean useCatalogBeforeSchema;
    /**
     * The PK of the last record that was passed to Kafka Connect. In case of
     * connector restart the start of the first chunk will be populated from it.
     */
    private Object[] lastEventKeySent;

    private String currentChunkId;

    /**
     * The largest PK in the table at the start of snapshot.
     */
    private Object[] maximumKey;

    public AbstractIncrementalSnapshotContext(boolean useCatalogBeforeSchema) {
        this.useCatalogBeforeSchema = useCatalogBeforeSchema;
    }

    public boolean openWindow(String id) {
        if (notExpectedChunk(id)) {
            LOGGER.info("Received request to open window with id = '{}', expected = '{}', request ignored", id, currentChunkId);
            return false;
        }
        LOGGER.debug("Opening window for incremental snapshot chunk");
        windowOpened = true;
        return true;
    }

    public boolean closeWindow(String id) {
        if (notExpectedChunk(id)) {
            LOGGER.info("Received request to close window with id = '{}', expected = '{}', request ignored", id, currentChunkId);
            return false;
        }
        LOGGER.debug("Closing window for incremental snapshot chunk");
        windowOpened = false;
        return true;
    }

    /**
     * The snapshotting process can receive out-of-order windowing signals after connector restart
     * as depending on committed offset position some signals can be replayed.
     * In extreme case a signal can be received even when the incremental snapshot was completed just
     * before the restart.
     * Such windowing signals are ignored.
     */
    private boolean notExpectedChunk(String id) {
        return currentChunkId == null || !id.startsWith(currentChunkId);
    }

    public boolean deduplicationNeeded() {
        return windowOpened;
    }

    private String arrayToSerializedString(Object[] array) {
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(array);
            return HexConverter.convertToHexString(bos.toByteArray());
        }
        catch (IOException e) {
            throw new DebeziumException(String.format("Cannot serialize chunk information %s", array));
        }
    }

    private Object[] serializedStringToArray(String field, String serialized) {
        try (final ByteArrayInputStream bis = new ByteArrayInputStream(HexConverter.convertFromHex(serialized));
                ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (Object[]) ois.readObject();
        }
        catch (Exception e) {
            throw new DebeziumException(String.format("Failed to deserialize '%s' with value '%s'", field, serialized),
                    e);
        }
    }

    private String dataCollectionsToSnapshotAsString() {
        // TODO Handle non-standard table ids containing dots, commas etc.
        return dataCollectionsToSnapshot.stream().map(Object::toString).collect(Collectors.joining(","));
    }

    private List<String> stringToDataCollections(String dataCollectionsStr) {
        return Arrays.asList(dataCollectionsStr.split(","));
    }

    public boolean snapshotRunning() {
        return !dataCollectionsToSnapshot.isEmpty();
    }

    public Map<String, Object> store(Map<String, Object> offset) {
        if (!snapshotRunning()) {
            return offset;
        }
        offset.put(EVENT_PRIMARY_KEY, arrayToSerializedString(lastEventKeySent));
        offset.put(TABLE_MAXIMUM_KEY, arrayToSerializedString(maximumKey));
        offset.put(DATA_COLLECTIONS_TO_SNAPSHOT_KEY, dataCollectionsToSnapshotAsString());
        return offset;
    }

    private void addTablesIdsToSnapshot(List<T> dataCollectionIds) {
        dataCollectionsToSnapshot.addAll(dataCollectionIds);
    }

    @SuppressWarnings("unchecked")
    public List<T> addDataCollectionNamesToSnapshot(List<String> dataCollectionIds) {
        final List<T> newDataCollectionIds = dataCollectionIds.stream()
                .map(x -> (T) TableId.parse(x, useCatalogBeforeSchema))
                .collect(Collectors.toList());
        addTablesIdsToSnapshot(newDataCollectionIds);
        return newDataCollectionIds;
    }

    protected static <U> IncrementalSnapshotContext<U> init(AbstractIncrementalSnapshotContext<U> context, Map<String, ?> offsets) {
        final String lastEventSentKeyStr = (String) offsets.get(EVENT_PRIMARY_KEY);
        context.chunkEndPosition = (lastEventSentKeyStr != null)
                ? context.serializedStringToArray(EVENT_PRIMARY_KEY, lastEventSentKeyStr)
                : null;
        context.lastEventKeySent = null;
        final String maximumKeyStr = (String) offsets.get(TABLE_MAXIMUM_KEY);
        context.maximumKey = (maximumKeyStr != null) ? context.serializedStringToArray(TABLE_MAXIMUM_KEY, maximumKeyStr)
                : null;
        final String dataCollectionsStr = (String) offsets.get(DATA_COLLECTIONS_TO_SNAPSHOT_KEY);
        context.dataCollectionsToSnapshot.clear();
        if (dataCollectionsStr != null) {
            context.addDataCollectionNamesToSnapshot(context.stringToDataCollections(dataCollectionsStr));
        }
        return context;
    }

    public void sendEvent(Object[] key) {
        lastEventKeySent = key;
    }

    public T currentDataCollectionId() {
        return dataCollectionsToSnapshot.peek();
    }

    public int tablesToBeSnapshottedCount() {
        return dataCollectionsToSnapshot.size();
    }

    public void nextChunkPosition(Object[] end) {
        chunkEndPosition = end;
    }

    public Object[] chunkEndPosititon() {
        return chunkEndPosition;
    }

    private void resetChunk() {
        lastEventKeySent = null;
        chunkEndPosition = null;
        maximumKey = null;
    }

    public void revertChunk() {
        chunkEndPosition = lastEventKeySent;
        windowOpened = false;
    }

    public boolean isNonInitialChunk() {
        return chunkEndPosition != null;
    }

    public T nextDataCollection() {
        resetChunk();
        return dataCollectionsToSnapshot.poll();
    }

    public void startNewChunk() {
        currentChunkId = UUID.randomUUID().toString();
        LOGGER.debug("Starting new chunk with id '{}'", currentChunkId);
    }

    public String currentChunkId() {
        return currentChunkId;
    }

    public void maximumKey(Object[] key) {
        maximumKey = key;
    }

    public Optional<Object[]> maximumKey() {
        return Optional.ofNullable(maximumKey);
    }

    @Override
    public String toString() {
        return "IncrementalSnapshotContext [windowOpened=" + windowOpened + ", chunkEndPosition="
                + Arrays.toString(chunkEndPosition) + ", dataCollectionsToSnapshot=" + dataCollectionsToSnapshot
                + ", lastEventKeySent=" + Arrays.toString(lastEventKeySent) + ", maximumKey="
                + Arrays.toString(maximumKey) + "]";
    }
}
