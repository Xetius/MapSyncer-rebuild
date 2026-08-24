package com.mapsyncer.mca;

import com.mapsyncer.nbt.Tag;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A block that is not in the registry, kept with its raw NBT.
 *
 * <p>Modelled on Xaero's UnknownBlockState: wraps a block the registry does not know
 * about and keeps its original NBT so it can be written back out.</p>
 *
 * <p>What it is for:</p>
 * <ul>
 *   <li>blocks added by mods, which may not be in the registry</li>
 *   <li>keeping a block's full NBT so it serialises correctly</li>
 *   <li>answering basic questions about the block</li>
 * </ul>
 *
 * @deprecated Currently unused, kept as a fallback.
 *             Conversion goes through ChunkSectionParser.BlockState instead, and modded
 *             blocks are classified by matching their names.
 *             This class is retained for cases that may come up later:
 *             1. needing a block's full NBT preserved for serialisation
 *             2. name matching in BlockClassifier proving insufficient
 *             3. needing to stay compatible with Xaero's UnknownBlockState
 *
 * @see com.mapsyncer.mca.ChunkSectionParser.BlockState the block state actually in use
 * @see com.mapsyncer.nbt.Tag.Compound the NBT compound tag
 */
@Deprecated(since = "2026-05-24", forRemoval = false)
public class UnknownBlockStateWrapper {

    /**
     * The block's registry name, e.g. {@code "minecraft:stone"}.
     */
    private final String blockName;

    /**
     * The block's properties, e.g. {@code {snowy: "false", facing: "north"}}.
     */
    private final Map<String, String> properties;

    /**
     * The original NBT compound.
     */
    private final Tag.Compound originalNbt;

    /**
     * Cached string form, used by toString.
     */
    private final String stringRepresentation;

    /**
     * Builds one from an NBT compound.
     *
     * <p>Reads the block name and properties out of the NBT and builds the string form.</p>
     *
     * @param nbt the block's NBT, which must contain a "Name" field
     */
    public UnknownBlockStateWrapper(Tag.Compound nbt) {
        this.originalNbt = nbt;
        this.blockName = nbt.getString("Name");

        // Read the properties.
        Map<String, String> props = new java.util.LinkedHashMap<>();
        if (nbt.contains("Properties", Tag.TAG_COMPOUND)) {
            Tag.Compound propsTag = nbt.getCompound("Properties");
            for (Map.Entry<String, Tag> entry : propsTag.children().entrySet()) {
                Tag propTag = entry.getValue();
                if (propTag instanceof Tag.StringTag str) {
                    props.put(entry.getKey(), str.value());
                }
            }
        }
        this.properties = props;

        this.stringRepresentation = "Unknown: " + blockName + (props.isEmpty() ? "" : props.toString());
    }

    /**
     * Builds one from a name and properties.
     *
     * <p>For when the name and properties are known but there is no original NBT.</p>
     *
     * @param blockName the block's registry name, e.g. {@code "minecraft:stone"}
     * @param properties the block's properties; may be empty
     */
    public UnknownBlockStateWrapper(String blockName, Map<String, String> properties) {
        this.blockName = blockName;
        this.properties = properties;
        this.originalNbt = null;
        this.stringRepresentation = "Unknown: " + blockName + (properties.isEmpty() ? "" : properties.toString());
    }

    /**
     * The block's registry name.
     *
     * @return the name, e.g. {@code "minecraft:stone"}
     */
    public String getBlockName() {
        return blockName;
    }

    /**
     * The block's properties.
     *
     * @return every block state property
     */
    public Map<String, String> getProperties() {
        return properties;
    }

    /**
     * The original NBT.
     *
     * <p>Present when this was built from NBT; {@code null} otherwise.</p>
     *
     * @return the original NBT compound, or {@code null}
     */
    public Tag.Compound getOriginalNbt() {
        return originalNbt;
    }

    /**
     * Writes this block state to an output stream.
     *
     * <p>Writes the original NBT if there is any, otherwise builds NBT from the name and
     * properties.</p>
     *
     * @param out the output stream
     * @throws IOException if writing fails
     */
    public void write(DataOutputStream out) throws IOException {
        if (originalNbt != null) {
            // Write the original NBT.
            writeNbtCompound(originalNbt, out);
        } else {
            // Or build it.
            out.writeByte(10);  // TAG_Compound
            out.writeShort(0);  // empty name
            out.writeByte(8);   // TAG_String
            out.writeUTF("Name");
            out.writeUTF(blockName);

            if (!properties.isEmpty()) {
                out.writeByte(10);  // TAG_Compound for Properties
                out.writeUTF("Properties");
                for (Map.Entry<String, String> entry : properties.entrySet()) {
                    out.writeByte(8);  // TAG_String
                    out.writeUTF(entry.getKey());
                    out.writeUTF(entry.getValue());
                }
                out.writeByte(0);  // TAG_End for Properties
            }

            out.writeByte(0);  // TAG_End
        }
    }

    /**
     * Writes an NBT compound to an output stream.
     *
     * <p>Recurses through every child tag: strings, integers and bytes, as well as
     * compounds, lists and arrays.</p>
     *
     * @param compound the compound to write
     * @param out the output stream
     * @throws IOException if writing fails
     */
    private void writeNbtCompound(Tag.Compound compound, DataOutputStream out) throws IOException {
        out.writeByte(10);  // TAG_Compound
        out.writeShort(0);  // empty name

        for (Map.Entry<String, Tag> entry : compound.children().entrySet()) {
            Tag tag = entry.getValue();
            writeTag(entry.getKey(), tag, out);
        }

        out.writeByte(0);  // TAG_End
    }

    /**
     * Writes one NBT tag to an output stream.
     *
     * <p>Writes the type marker, the name and the value.</p>
     *
     * @param name the tag name
     * @param tag the tag
     * @param out the output stream
     * @throws IOException if writing fails
     */
    private void writeTag(String name, Tag tag, DataOutputStream out) throws IOException {
        if (tag instanceof Tag.StringTag str) {
            out.writeByte(8);
            out.writeUTF(name);
            out.writeUTF(str.value());
        } else if (tag instanceof Tag.Int intTag) {
            out.writeByte(3);
            out.writeUTF(name);
            out.writeInt(intTag.value());
        } else if (tag instanceof Tag.Byte byteTag) {
            out.writeByte(1);
            out.writeUTF(name);
            out.writeByte(byteTag.value());
        } else if (tag instanceof Tag.Short shortTag) {
            out.writeByte(2);
            out.writeUTF(name);
            out.writeShort(shortTag.value());
        } else if (tag instanceof Tag.Long longTag) {
            out.writeByte(4);
            out.writeUTF(name);
            out.writeLong(longTag.value());
        } else if (tag instanceof Tag.Float floatTag) {
            out.writeByte(5);
            out.writeUTF(name);
            out.writeFloat(floatTag.value());
        } else if (tag instanceof Tag.Double doubleTag) {
            out.writeByte(6);
            out.writeUTF(name);
            out.writeDouble(doubleTag.value());
        } else if (tag instanceof Tag.Compound compoundTag) {
            out.writeByte(10);
            out.writeUTF(name);
            writeNbtCompound(compoundTag, out);
        } else if (tag instanceof Tag.LongArray longArray) {
            out.writeByte(12);
            out.writeUTF(name);
            out.writeInt(longArray.value().length);
            for (long l : longArray.value()) {
                out.writeLong(l);
            }
        } else if (tag instanceof Tag.IntArray intArray) {
            out.writeByte(11);
            out.writeUTF(name);
            out.writeInt(intArray.value().length);
            for (int i : intArray.value()) {
                out.writeInt(i);
            }
        } else if (tag instanceof Tag.ByteArray byteArray) {
            out.writeByte(7);
            out.writeUTF(name);
            out.writeInt(byteArray.value().length);
            out.write(byteArray.value());
        } else if (tag instanceof Tag.ListTag list) {
            out.writeByte(9);
            out.writeUTF(name);
            byte elementType = list.elementType();
            out.writeByte(elementType);
            List<Tag> items = list.items();
            out.writeInt(items.size());
            for (Tag item : items) {
                // List elements carry no name.
                writeListElement(item, out);
            }
        }
    }

    /**
     * Writes a list element, which carries no name.
     *
     * <p>Only the type marker and the value.</p>
     *
     * @param tag the tag
     * @param out the output stream
     * @throws IOException if writing fails
     */
    private void writeListElement(Tag tag, DataOutputStream out) throws IOException {
        if (tag instanceof Tag.StringTag str) {
            out.writeUTF(str.value());
        } else if (tag instanceof Tag.Int intTag) {
            out.writeInt(intTag.value());
        } else if (tag instanceof Tag.Byte byteTag) {
            out.writeByte(byteTag.value());
        } else if (tag instanceof Tag.Short shortTag) {
            out.writeShort(shortTag.value());
        } else if (tag instanceof Tag.Long longTag) {
            out.writeLong(longTag.value());
        } else if (tag instanceof Tag.Float floatTag) {
            out.writeFloat(floatTag.value());
        } else if (tag instanceof Tag.Double doubleTag) {
            out.writeDouble(doubleTag.value());
        } else if (tag instanceof Tag.Compound compoundTag) {
            writeNbtCompound(compoundTag, out);
        } else if (tag instanceof Tag.LongArray longArray) {
            out.writeInt(longArray.value().length);
            for (long l : longArray.value()) {
                out.writeLong(l);
            }
        } else if (tag instanceof Tag.IntArray intArray) {
            out.writeInt(intArray.value().length);
            for (int i : intArray.value()) {
                out.writeInt(i);
            }
        } else if (tag instanceof Tag.ByteArray byteArray) {
            out.writeInt(byteArray.value().length);
            out.write(byteArray.value());
        }
    }

    /**
     * A readable form of this block state.
     *
     * @return {@code "Unknown: blockName{properties}"}
     */
    @Override
    public String toString() {
        return stringRepresentation;
    }

    /**
     * Whether this is air.
     *
     * <p>An unknown block is never treated as air.</p>
     *
     * @return always {@code false}
     */
    public boolean isAir() {
        return false;
    }

    /**
     * Whether this is a fluid.
     *
     * <p>An unknown block is never treated as a fluid.</p>
     *
     * @return always {@code false}
     */
    public boolean isFluid() {
        return false;
    }

    /**
     * Whether this is water.
     *
     * <p>Decided by looking for "water" in the block name.</p>
     *
     * @return {@code true} if the name contains "water"
     */
    public boolean isWater() {
        return blockName.contains("water");
    }

    /**
     * Whether this is lava.
     *
     * <p>Decided by looking for "lava" in the block name.</p>
     *
     * @return {@code true} if the name contains "lava"
     */
    public boolean isLava() {
        return blockName.contains("lava");
    }
}