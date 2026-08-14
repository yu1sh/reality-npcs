package io.github.yu1sh.reality.npcs;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(RealityNpcsMod.MOD_ID)
public final class RealityNpcsMod {
    public static final String MOD_ID = "reality_npcs";

    public RealityNpcsMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        NpcMenus.register(modEventBus);
        NpcNetwork.register();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        NpcManager.registerCommands(event);
    }

    @SubscribeEvent
    public void serverStarted(ServerStartedEvent event) {
        NpcManager.restore(event.getServer());
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            NpcManager.tick(event.getServer());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void cancelGuideInteraction(PlayerInteractEvent.EntityInteract event) {
        cancelGuideInteraction(event, event.getTarget());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void cancelGuideInteractionSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        cancelGuideInteraction(event, event.getTarget());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void cancelGuideAttack(AttackEntityEvent event) {
        if (!event.getEntity().level().isClientSide && NpcManager.isTrackedGuide(event.getTarget())) {
            event.setCanceled(true);
            notifyGuideInteraction(event.getEntity(), event.getTarget());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void cancelGuideDamage(LivingAttackEvent event) {
        if (!event.getEntity().level().isClientSide
                && NpcManager.isTrackedGuideInAnyState(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void cancelGuideDimensionTravel(EntityTravelToDimensionEvent event) {
        if (NpcManager.isTrackedGuideInAnyState(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private static void cancelGuideInteraction(
            PlayerInteractEvent event,
            Entity target) {
        if (event.getLevel().isClientSide) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (NpcGuideController.openGui(player, target)
                || NpcManager.isTrackedGuideInAnyState(target)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
            if (NpcManager.activeGuideForInteraction(target) == null) {
                notifyGuideUnavailable(player);
            }
        }
    }

    private static void notifyGuideUnavailable(Player player) {
        player.sendSystemMessage(Component.translatable("reality_npcs.guide.unavailable"));
    }

    private static void notifyGuideInteraction(Player player, Entity target) {
        player.sendSystemMessage(Component.translatable("reality_npcs.guide.combat_blocked"));
    }
}
