package io.github.yu1sh.reality.npcs;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

final class NpcMenus {
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(
            ForgeRegistries.MENU_TYPES,
            RealityNpcsMod.MOD_ID);

    static final RegistryObject<MenuType<NpcAdminMenu>> NPC_ADMIN = MENUS.register(
            "npc_admin",
            () -> IForgeMenuType.create((windowId, inventory, data) ->
                    new NpcAdminMenu(windowId, inventory, data.readLong())));

    private NpcMenus() {
    }

    static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
