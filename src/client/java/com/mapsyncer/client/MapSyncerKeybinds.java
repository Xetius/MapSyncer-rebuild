package com.mapsyncer.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

final class MapSyncerKeybinds {
    private static KeyMapping openGui;

    private MapSyncerKeybinds() {
    }

    static void register() {
        openGui = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mapsyncer.open_gui",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGui.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.setScreenAndShow(new MapSyncerScreen());
                }
            }
        });
    }
}
