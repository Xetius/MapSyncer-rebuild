package com.mapsyncer.nbt;

import com.mapsyncer.util.BoundedStringPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads Minecraft NBT. No dependencies.
 *
 * <p>Parses the binary NBT (Named Binary Tag) format. Everything is big-endian, as the
 * format specifies.</p>
 *
 * <p>Safety limits: array sizes, list lengths and nesting depth are all bounded, so that
 * malformed or malicious NBT cannot exhaust memory. If legitimate data ever trips one of
 * these, the log message is worth reporting.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * try (NbtReader reader = new NbtReader(inputStream)) {
 *     Tag.Compound root = reader.readDocument();
 *     // work with the NBT...
 * }
 * }</pre>
 *
 * @see Tag
 */
public class NbtReader implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NbtReader.class);

    /**
     * Largest array (ByteArray, IntArray, LongArray) that will be read.
     *
     * <p>Real data peaks around 25,000 entries, from the block_states.data of every section
     * in a chunk; this allows five times that.</p>
     */
    private static final int MAX_ARRAY_SIZE = 125_000;

    /**
     * Longest list that will be read.
     *
     * <p>Real data peaks around 1,000 entries, from a block palette; this allows five times
     * that.</p>
     */
    private static final int MAX_LIST_SIZE = 5_000;

    /**
     * Deepest compound nesting that will be read.
     *
     * <p>Real data goes about 5-6 levels deep (chunk, sections, section, block_states,
     * palette); this allows five times that.</p>
     */
    private static final int MAX_COMPOUND_DEPTH = 30;

    /**
     * Maximum modified-UTF payload size accepted for strings that are kept.
     *
     * <p>Chunk map generation only needs short identifiers such as block,
     * biome, status, and heightmap names. Very large strings are skipped as a
     * corrupt or irrelevant chunk payload instead of letting readUTF allocate
     * until the generator thread runs out of heap.</p>
     */
    private static final int MAX_STRING_UTF_BYTES = 32_767;

    private static final Set<String> SKIPPED_TAG_NAMES = Set.of(
            "fluid_ticks",
            "block_ticks",
            "TileTicks",
            "ToBeTicked",
            "LiquidsToBeTicked",
            "entities",
            "Entities",
            "block_entities",
            "TileEntities",
            "PostProcessing",
            "Lights",
            "CarvingMasks",
            "structures"
    );

    /** The stream being read. */
    private final DataInputStream in;

    /** Current nesting depth. */
    private int currentDepth = 0;

    /**
     * Creates a reader.
     *
     * @param in a stream of binary NBT
     */
    public NbtReader(InputStream in) {
        this.in = new DataInputStream(in);
    }

    /**
     * Reads a whole NBT document, i.e. the root compound.
     *
     * <p>An NBT document always starts with a compound.</p>
     *
     * @return the root compound
     * @throws IOException if reading fails or the document is malformed
     */
    public Tag.Compound readDocument() throws IOException {
        byte type = in.readByte();
        if (type != Tag.TAG_COMPOUND) {
            throw new IOException("NBT document must start with a compound, but starts with type: " + type);
        }
        String name = readUtf();
        return readCompoundContent(name);
    }

    /**
     * Reads one tag, including its type and name.
     *
     * <p>Reads the type marker, the name and the value.</p>
     *
     * @return the tag
     * @throws IOException if reading fails
     */
    public Tag readTag() throws IOException {
        byte type = in.readByte();
        if (type == Tag.TAG_END) {
            return new Tag.End();
        }
        String name = readUtf();
        return readPayload(type, name);
    }

    /**
     * Reads a tag's value, with the type and name already known.
     *
     * <p>Reads whatever the given type calls for.</p>
     *
     * @param type the NBT type ID
     * @param name the tag name
     * @return the tag
     * @throws IOException if reading fails or the type is unknown
     */
    private Tag readPayload(byte type, String name) throws IOException {
        switch (type) {
            case Tag.TAG_END:
                return new Tag.End();
            case Tag.TAG_BYTE:
                return new Tag.Byte(name, in.readByte());
            case Tag.TAG_SHORT:
                return new Tag.Short(name, in.readShort());
            case Tag.TAG_INT:
                return new Tag.Int(name, in.readInt());
            case Tag.TAG_LONG:
                return new Tag.Long(name, in.readLong());
            case Tag.TAG_FLOAT:
                return new Tag.Float(name, in.readFloat());
            case Tag.TAG_DOUBLE:
                return new Tag.Double(name, in.readDouble());
            case Tag.TAG_BYTE_ARRAY:
                return readByteArray(name);
            case Tag.TAG_STRING:
                return new Tag.StringTag(name, readUtf());
            case Tag.TAG_LIST:
                return readListContent(name);
            case Tag.TAG_COMPOUND:
                return readCompoundContent(name);
            case Tag.TAG_INT_ARRAY:
                return readIntArray(name);
            case Tag.TAG_LONG_ARRAY:
                return readLongArray(name);
            default:
                throw new IOException("Unknown NBT type: " + type);
        }
    }

    /**
     * Reads a ByteArray tag.
     *
     * @param name the tag name
     * @return the tag
     * @throws IOException if reading fails or the array is too long
     */
    private Tag.ByteArray readByteArray(String name) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("ByteArray length cannot be negative: " + length);
        }
        if (length > MAX_ARRAY_SIZE) {
            LOGGER.warn("NBT size limit exceeded: ByteArray '{}' length={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, length, MAX_ARRAY_SIZE);
            throw new IOException("ByteArray too long: " + length + " (max " + MAX_ARRAY_SIZE + ")");
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return new Tag.ByteArray(name, data);
    }

    /**
     * Reads an IntArray tag.
     *
     * @param name the tag name
     * @return the tag
     * @throws IOException if reading fails or the array is too long
     */
    private Tag.IntArray readIntArray(String name) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("IntArray length cannot be negative: " + length);
        }
        if (length > MAX_ARRAY_SIZE) {
            LOGGER.warn("NBT size limit exceeded: IntArray '{}' length={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, length, MAX_ARRAY_SIZE);
            throw new IOException("IntArray too long: " + length + " (max " + MAX_ARRAY_SIZE + ")");
        }
        int[] data = new int[length];
        for (int i = 0; i < length; i++) {
            data[i] = in.readInt();
        }
        return new Tag.IntArray(name, data);
    }

    /**
     * Reads a LongArray tag.
     *
     * @param name the tag name
     * @return the tag
     * @throws IOException if reading fails or the array is too long
     */
    private Tag.LongArray readLongArray(String name) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("LongArray length cannot be negative: " + length);
        }
        if (length > MAX_ARRAY_SIZE) {
            LOGGER.warn("NBT size limit exceeded: LongArray '{}' length={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, length, MAX_ARRAY_SIZE);
            throw new IOException("LongArray too long: " + length + " (max " + MAX_ARRAY_SIZE + ")");
        }
        long[] data = new long[length];
        for (int i = 0; i < length; i++) {
            data[i] = in.readLong();
        }
        return new Tag.LongArray(name, data);
    }

    /**
     * Reads a List tag's contents.
     *
     * <p>Every element shares one type, and elements have no names, so they are read with
     * an empty name.</p>
     *
     * @param name the tag name
     * @return the tag
     * @throws IOException if reading fails or the list is too long
     */
    private Tag.ListTag readListContent(String name) throws IOException {
        byte elementType = in.readByte();
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("List length cannot be negative: " + length);
        }
        if (length > MAX_LIST_SIZE) {
            LOGGER.warn("NBT size limit exceeded: List '{}' length={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, length, MAX_LIST_SIZE);
            throw new IOException("List too long: " + length + " (max " + MAX_LIST_SIZE + ")");
        }
        List<Tag> items = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            // List elements have no name.
            items.add(readPayload(elementType, ""));
        }
        return new Tag.ListTag(name, elementType, items);
    }

    /**
     * Reads a Compound tag's contents.
     *
     * <p>A compound is a set of named tags terminated by TAG_END. Children keep the order
     * they were read in.</p>
     *
     * <p>Nesting depth is checked, so recursion cannot run away.</p>
     *
     * @param name the tag name
     * @return the tag
     * @throws IOException if reading fails or nesting is too deep
     */
    private Tag.Compound readCompoundContent(String name) throws IOException {
        currentDepth++;
        if (currentDepth > MAX_COMPOUND_DEPTH) {
            LOGGER.warn("NBT depth limit exceeded: Compound '{}' depth={}, max={}. " +
                    "Please report this with the MCA file location for analysis.", name, currentDepth, MAX_COMPOUND_DEPTH);
            throw new IOException("Compound nested too deeply: " + currentDepth + " (max " + MAX_COMPOUND_DEPTH + ")");
        }

        Map<String, Tag> children = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == Tag.TAG_END) {
                currentDepth--;
                break;
            }
            String childName = readUtf();
            if (shouldSkipTag(childName)) {
                skipPayload(type);
                continue;
            }
            children.put(childName, readPayload(type, childName));
        }
        return new Tag.Compound(name, children);
    }

    private boolean shouldSkipTag(String name) {
        return SKIPPED_TAG_NAMES.contains(name);
    }

    private String readUtf() throws IOException {
        int utfLength = in.readUnsignedShort();
        if (utfLength > MAX_STRING_UTF_BYTES) {
            skipFully(utfLength);
            throw new IOException("NBT string length exceeded: " + utfLength + " (max " + MAX_STRING_UTF_BYTES + ")");
        }

        byte[] utfData = new byte[utfLength + 2];
        utfData[0] = (byte) ((utfLength >>> 8) & 0xFF);
        utfData[1] = (byte) (utfLength & 0xFF);
        in.readFully(utfData, 2, utfLength);
        try (DataInputStream utfIn = new DataInputStream(new ByteArrayInputStream(utfData))) {
            return BoundedStringPool.canonicalize(utfIn.readUTF());
        } catch (OutOfMemoryError e) {
            throw new IOException("Failed to decode NBT string: Java heap space", e);
        }
    }

    private void skipPayload(byte type) throws IOException {
        switch (type) {
            case Tag.TAG_END:
                return;
            case Tag.TAG_BYTE:
                skipFully(1);
                return;
            case Tag.TAG_SHORT:
                skipFully(2);
                return;
            case Tag.TAG_INT:
            case Tag.TAG_FLOAT:
                skipFully(4);
                return;
            case Tag.TAG_LONG:
            case Tag.TAG_DOUBLE:
                skipFully(8);
                return;
            case Tag.TAG_BYTE_ARRAY: {
                int length = readNonNegativeLength("ByteArray");
                skipFully(length);
                return;
            }
            case Tag.TAG_STRING: {
                int length = in.readUnsignedShort();
                skipFully(length);
                return;
            }
            case Tag.TAG_LIST: {
                byte elementType = in.readByte();
                int length = readNonNegativeLength("List");
                for (int i = 0; i < length; i++) {
                    skipPayload(elementType);
                }
                return;
            }
            case Tag.TAG_COMPOUND:
                while (true) {
                    byte childType = in.readByte();
                    if (childType == Tag.TAG_END) {
                        return;
                    }
                    skipPayload(Tag.TAG_STRING);
                    skipPayload(childType);
                }
            case Tag.TAG_INT_ARRAY: {
                int length = readNonNegativeLength("IntArray");
                skipFully((long) length * Integer.BYTES);
                return;
            }
            case Tag.TAG_LONG_ARRAY: {
                int length = readNonNegativeLength("LongArray");
                skipFully((long) length * Long.BYTES);
                return;
            }
            default:
                throw new IOException("Unknown NBT type: " + type);
        }
    }

    private int readNonNegativeLength(String tagType) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException(tagType + " length cannot be negative: " + length);
        }
        return length;
    }

    private void skipFully(long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new EOFException("Unexpected EOF while skipping NBT payload");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    /**
     * Closes the reader and releases the stream.
     *
     * @throws IOException if closing fails
     */
    @Override
    public void close() throws IOException {
        in.close();
    }
}
