package com.mapsyncer.nbt;

import java.util.List;
import java.util.Map;

/**
 * The NBT tag types. No dependencies.
 *
 * <p>Every tag type Minecraft's NBT format uses, as a sealed interface so the compiler
 * can check exhaustiveness.</p>
 *
 * <p>The types:</p>
 * <ul>
 *   <li>{@link End} - marks the end of a compound</li>
 *   <li>{@link Byte} - signed 8-bit integer</li>
 *   <li>{@link Short} - signed 16-bit integer</li>
 *   <li>{@link Int} - signed 32-bit integer</li>
 *   <li>{@link Long} - signed 64-bit integer</li>
 *   <li>{@link Float} - 32-bit IEEE 754 float</li>
 *   <li>{@link Double} - 64-bit IEEE 754 double</li>
 *   <li>{@link ByteArray} - byte array</li>
 *   <li>{@link StringTag} - UTF-8 string</li>
 *   <li>{@link ListTag} - list of tags, all the same type</li>
 *   <li>{@link Compound} - map of name to tag</li>
 *   <li>{@link IntArray} - int array</li>
 *   <li>{@link LongArray} - long array</li>
 * </ul>
 *
 * @see NbtReader
 */
public sealed interface Tag permits
    Tag.End,
    Tag.Byte,
    Tag.Short,
    Tag.Int,
    Tag.Long,
    Tag.Float,
    Tag.Double,
    Tag.ByteArray,
    Tag.StringTag,
    Tag.ListTag,
    Tag.Compound,
    Tag.IntArray,
    Tag.LongArray {

    /**
     * This tag's type ID.
     *
     * @return the type ID, 0-12
     */
    byte typeId();

    /**
     * This tag's name.
     *
     * <p>Usually empty for the root compound.</p>
     *
     * @return the tag name
     */
    String name();

    // ========== Type IDs ==========

    /** End marker, which closes a compound. */
    byte TAG_END = 0;
    /** Byte: signed 8-bit integer. */
    byte TAG_BYTE = 1;
    /** Short: signed 16-bit integer. */
    byte TAG_SHORT = 2;
    /** Int: signed 32-bit integer. */
    byte TAG_INT = 3;
    /** Long: signed 64-bit integer. */
    byte TAG_LONG = 4;
    /** Float: 32-bit IEEE 754. */
    byte TAG_FLOAT = 5;
    /** Double: 64-bit IEEE 754. */
    byte TAG_DOUBLE = 6;
    /** Byte array. */
    byte TAG_BYTE_ARRAY = 7;
    /** String, UTF-8 encoded. */
    byte TAG_STRING = 8;
    /** List of tags, all the same type. */
    byte TAG_LIST = 9;
    /** Compound: a map of name to tag. */
    byte TAG_COMPOUND = 10;
    /** Int array. */
    byte TAG_INT_ARRAY = 11;
    /** Long array. */
    byte TAG_LONG_ARRAY = 12;

    // ========== The tags themselves ==========

    /**
     * TAG_End: closes a compound.
     *
     * <p>Carries no data.</p>
     */
    record End() implements Tag {
        @Override public byte typeId() { return TAG_END; }
        @Override public String name() { return ""; }
    }

    /**
     * TAG_Byte: signed 8-bit integer.
     *
     * @param name  the tag name
     * @param value the value, -128 to 127
     */
    record Byte(String name, byte value) implements Tag {
        @Override public byte typeId() { return TAG_BYTE; }
    }

    /**
     * TAG_Short: signed 16-bit integer.
     *
     * @param name  the tag name
     * @param value the value, -32768 to 32767
     */
    record Short(String name, short value) implements Tag {
        @Override public byte typeId() { return TAG_SHORT; }
    }

    /**
     * TAG_Int: signed 32-bit integer.
     *
     * @param name  the tag name
     * @param value the value
     */
    record Int(String name, int value) implements Tag {
        @Override public byte typeId() { return TAG_INT; }
    }

    /**
     * TAG_Long: signed 64-bit integer.
     *
     * @param name  the tag name
     * @param value the value
     */
    record Long(String name, long value) implements Tag {
        @Override public byte typeId() { return TAG_LONG; }
    }

    /**
     * TAG_Float: 32-bit IEEE 754.
     *
     * @param name  the tag name
     * @param value the value
     */
    record Float(String name, float value) implements Tag {
        @Override public byte typeId() { return TAG_FLOAT; }
    }

    /**
     * TAG_Double: 64-bit IEEE 754.
     *
     * @param name  the tag name
     * @param value the value
     */
    record Double(String name, double value) implements Tag {
        @Override public byte typeId() { return TAG_DOUBLE; }
    }

    /**
     * TAG_Byte_Array.
     *
     * @param name  the tag name
     * @param value the bytes
     */
    record ByteArray(String name, byte[] value) implements Tag {
        @Override public byte typeId() { return TAG_BYTE_ARRAY; }
    }

    /**
     * TAG_String: UTF-8.
     *
     * @param name  the tag name
     * @param value the string
     */
    record StringTag(String name, String value) implements Tag {
        @Override public byte typeId() { return TAG_STRING; }
    }

    /**
     * TAG_List: a list of tags, all the same type.
     *
     * <p>Every element shares one type, and elements have no names of their own.</p>
     *
     * @param name        the tag name
     * @param elementType the type ID of the elements
     * @param items       the elements
     */
    record ListTag(String name, byte elementType, List<Tag> items) implements Tag {
        @Override public byte typeId() { return TAG_LIST; }
    }

    /**
     * TAG_Compound: a map of name to tag.
     *
     * <p>The workhorse of NBT. Each child tag has a unique name, which is its key.</p>
     *
     * @param name     the tag name
     * @param children the child tags, keyed by name
     */
    record Compound(String name, Map<String, Tag> children) implements Tag {
        @Override public byte typeId() { return TAG_COMPOUND; }

        // ========== Convenience accessors ==========

        /**
         * The tag stored under a key.
         *
         * @param key the key
         * @return the tag, or {@code null} if there is none
         */
        public Tag get(String key) { return children.get(key); }

        /**
         * Whether a key is present.
         *
         * @param key the key
         * @return {@code true} if present
         */
        public boolean contains(String key) { return children.containsKey(key); }

        /**
         * Whether a key is present and holds the expected type.
         *
         * @param key     the key
         * @param typeId  the expected type ID
         * @return {@code true} if present and of that type
         */
        public boolean contains(String key, byte typeId) {
            Tag t = children.get(key);
            return t != null && t.typeId() == typeId;
        }

        /**
         * A byte value.
         *
         * @param key the key
         * @return the value, or 0 if it is missing or of another type
         */
        public byte getByte(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.Byte b ? b.value() : 0;
        }

        /**
         * A short value.
         *
         * @param key the key
         * @return the value, or 0 if it is missing or of another type
         */
        public short getShort(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.Short s ? s.value() : 0;
        }

        /**
         * An int value.
         *
         * @param key the key
         * @return the value, or 0 if it is missing or of another type
         */
        public int getInt(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.Int i ? i.value() : 0;
        }

        /**
         * A long value.
         *
         * @param key the key
         * @return the value, or 0 if it is missing or of another type
         */
        public long getLong(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.Long l ? l.value() : 0;
        }

        /**
         * A string value.
         *
         * @param key the key
         * @return the value, or an empty string if it is missing or of another type
         */
        public String getString(String key) {
            Tag t = children.get(key);
            return t instanceof StringTag s ? s.value() : "";
        }

        /**
         * A child compound.
         *
         * @param key the key
         * @return the compound, or an empty one if it is missing or of another type
         */
        public Compound getCompound(String key) {
            Tag t = children.get(key);
            return t instanceof Compound c ? c : new Compound(key, Map.of());
        }

        /**
         * A child list.
         *
         * @param key          the key
         * @param expectedType the expected element type ID
         * @return the list, or an empty one if it is missing or of another type
         */
        public ListTag getList(String key, byte expectedType) {
            Tag t = children.get(key);
            return t instanceof ListTag l ? l : new ListTag(key, expectedType, List.of());
        }

        /**
         * A byte array value.
         *
         * @param key the key
         * @return the array, or an empty one if it is missing or of another type
         */
        public byte[] getByteArray(String key) {
            Tag t = children.get(key);
            return t instanceof ByteArray ba ? ba.value() : new byte[0];
        }

        /**
         * An int array value.
         *
         * @param key the key
         * @return the array, or an empty one if it is missing or of another type
         */
        public int[] getIntArray(String key) {
            Tag t = children.get(key);
            return t instanceof Tag.IntArray ia ? ia.value() : new int[0];
        }

        /**
         * A long array value.
         *
         * @param key the key
         * @return the array, or an empty one if it is missing or of another type
         */
        public long[] getLongArray(String key) {
            Tag t = children.get(key);
            return t instanceof LongArray la ? la.value() : new long[0];
        }
    }

    /**
     * TAG_Int_Array.
     *
     * @param name  the tag name
     * @param value the ints
     */
    record IntArray(String name, int[] value) implements Tag {
        @Override public byte typeId() { return TAG_INT_ARRAY; }
    }

    /**
     * TAG_Long_Array.
     *
     * @param name  the tag name
     * @param value the longs
     */
    record LongArray(String name, long[] value) implements Tag {
        @Override public byte typeId() { return TAG_LONG_ARRAY; }
    }
}