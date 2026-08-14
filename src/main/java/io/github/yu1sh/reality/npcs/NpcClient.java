package io.github.yu1sh.reality.npcs;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(
        modid = RealityNpcsMod.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class NpcClient {
    private NpcClient() {
    }

    @SubscribeEvent
    public static void registerScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(NpcMenus.NPC_ADMIN.get(), NpcAdminScreen::new);
            MenuScreens.register(NpcMenus.NPC_GUIDE.get(), NpcGuideScreen::new);
        });
    }

    public static void receiveSnapshot(NpcAdminSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof NpcAdminScreen screen) {
            screen.applySnapshot(snapshot);
        }
    }

    public static void receiveGuideSnapshot(NpcGuideSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof NpcGuideScreen screen) {
            screen.applySnapshot(snapshot);
        }
    }
}
