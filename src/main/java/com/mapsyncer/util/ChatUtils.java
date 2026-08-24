package com.mapsyncer.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Chat message helpers.
 *
 * One place for the mod's message prefix and colour scheme, shared by
 * CacheGenerateCommand and MapSyncerCommand.
 */
public final class ChatUtils {

    /** Mod prefix colour (gold). */
    public static final int PREFIX_COLOR = 0xFFE55E;

    /** Success message colour (green). */
    public static final int SUCCESS_COLOR = 0x55FF55;

    /** Error message colour (red). */
    public static final int ERROR_COLOR = 0xFF5555;

    /** Normal text colour (white). */
    public static final int NORMAL_COLOR = 0xFFFFFF;

    /** Description text colour (grey). */
    public static final int DESC_COLOR = 0xAAAAAA;

    /** Heading colour (yellow). */
    public static final int HEADER_COLOR = 0xFFFF55;

    /**
     * Utility class; not instantiable.
     */
    private ChatUtils() {
        // Utility class; not instantiable.
    }

    /**
     * The coloured mod prefix.
     *
     * @return the gold "MapSyncer" prefix
     */
    public static MutableComponent prefix() {
        return Component.translatable("mapsyncer.prefix").withStyle(style -> style.withColor(PREFIX_COLOR));
    }

    /**
     * A success message with the mod prefix.
     *
     * @param key translation key
     * @return prefix followed by the success message
     */
    public static MutableComponent success(String key) {
        return prefix().append(Component.translatable(key).withStyle(style -> style.withColor(SUCCESS_COLOR)));
    }

    /**
     * A success message with the mod prefix and arguments.
     *
     * @param key translation key
     * @param args translation arguments
     * @return prefix followed by the success message
     */
    public static MutableComponent success(String key, Object... args) {
        return prefix().append(Component.translatable(key, args).withStyle(style -> style.withColor(SUCCESS_COLOR)));
    }

    /**
     * An error message with the mod prefix.
     *
     * @param key translation key
     * @return prefix followed by the error message
     */
    public static MutableComponent error(String key) {
        return prefix().append(Component.translatable(key).withStyle(style -> style.withColor(ERROR_COLOR)));
    }

    /**
     * An error message with the mod prefix and arguments.
     *
     * @param key translation key
     * @param args translation arguments
     * @return prefix followed by the error message
     */
    public static MutableComponent error(String key, Object... args) {
        return prefix().append(Component.translatable(key, args).withStyle(style -> style.withColor(ERROR_COLOR)));
    }

    /**
     * A plain message with the mod prefix.
     *
     * @param key translation key
     * @return prefix followed by the message
     */
    public static MutableComponent message(String key) {
        return prefix().append(Component.translatable(key).withStyle(style -> style.withColor(NORMAL_COLOR)));
    }

    /**
     * A plain message with the mod prefix and arguments.
     *
     * @param key translation key
     * @param args translation arguments
     * @return prefix followed by the message
     */
    public static MutableComponent message(String key, Object... args) {
        return prefix().append(Component.translatable(key, args).withStyle(style -> style.withColor(NORMAL_COLOR)));
    }

    /**
     * Description text, without the prefix.
     *
     * @param key translation key
     * @return grey text
     */
    public static MutableComponent desc(String key) {
        return Component.translatable(key).withStyle(style -> style.withColor(DESC_COLOR));
    }

    /**
     * Description text with arguments, without the prefix.
     *
     * @param key translation key
     * @param args translation arguments
     * @return grey text
     */
    public static MutableComponent desc(String key, Object... args) {
        return Component.translatable(key, args).withStyle(style -> style.withColor(DESC_COLOR));
    }

    /**
     * A heading, without the prefix.
     *
     * @param key translation key
     * @return yellow heading text
     */
    public static MutableComponent header(String key) {
        return Component.translatable(key).withStyle(style -> style.withColor(HEADER_COLOR));
    }
}