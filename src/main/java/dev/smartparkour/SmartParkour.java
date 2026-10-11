package dev.smartparkour;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;

/** Fresh 0.8 controller. No teleportation, no persistent key overrides. */
public final class SmartParkour implements ClientModInitializer {
    private enum State { IDLE, ALIGN, APPROACH, FLIGHT, LAND }
    private static final double EDGE_MARGIN = 0.13;
    private static final double CENTER_TOLERANCE = 0.075;
    private static final int SEARCH_RADIUS = 6;
    private KeyMapping toggle;
    private KeyMapping settingsKey;
    // 20 client ticks = approximately one second at normal TPS.
    private int jumpDelayTicks = 10;
    private boolean enabled;
    private State state = State.IDLE;
    private BlockPos target;
    private int phaseTicks;
    private boolean jumpLatch;
    private boolean toggleWasDown;

    @Override public void onInitializeClient() {
        toggle = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.smartparkour.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, KeyMapping.Category.MISC));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.smartparkour.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }
    private void tick(Minecraft mc) {
        while (settingsKey.consumeClick()) {
            if (mc.screen instanceof SettingsScreen) mc.setScreen(null);
            else {
                // Pause automation and relinquish keys before opening a GUI.
                reset(mc);
                mc.setScreen(new SettingsScreen());
            }
        }
        // Handle both queued clicks and the rising edge of the bound key.
        // Some key conflicts or GUI transitions can consume a queued click.
        boolean clicked = false;
        while (toggle.consumeClick()) clicked = true;
        boolean down = toggle.isDown();
        if (mc.screen == null && (clicked || (down && !toggleWasDown))) {
            enabled = !enabled;
            reset(mc);
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("SmartParkour 0.8.4: " + (enabled ? "ON" : "OFF")), true);
        }
        toggleWasDown = down;
        if (!enabled || mc.player == null || mc.level == null) return;
        if (mc.screen != null) { release(mc); return; }
        if (!mc.player.onGround()) {
            if (state != State.FLIGHT) { state = State.FLIGHT; phaseTicks = 0; }
        }
        if (++phaseTicks > 120) { reset(mc); return; }
        switch (state) {
            case IDLE -> {
                if (!mc.player.onGround()) return;
                target = findTarget(mc);
                if (target != null) transition(State.ALIGN);
            }
            case ALIGN -> {
                if (target == null || !isTarget(mc, target)) { transition(State.IDLE); return; }
                Vec3 pos = mc.player.position();
                BlockPos under = mc.player.blockPosition().below();
                double cx = under.getX() + .5, cz = under.getZ() + .5;
                look(mc, target.getX() + .5, target.getZ() + .5);
                if (Math.hypot(cx - pos.x, cz - pos.z) > CENTER_TOLERANCE) {
                    steer(mc, cx - pos.x, cz - pos.z, false, false);
                } else { release(mc); transition(State.APPROACH); }
            }
            case APPROACH -> {
                if (target == null || !isTarget(mc, target)) { reset(mc); return; }
                Vec3 pos = mc.player.position();
                BlockPos under = mc.player.blockPosition().below();
                double dx = target.getX() + .5 - (under.getX() + .5);
                double dz = target.getZ() + .5 - (under.getZ() + .5);
                double dist = Math.hypot(dx, dz);
                if (dist < .001) { reset(mc); return; }
                double ux = dx / dist, uz = dz / dist;
                double edgeX = under.getX() + .5 + ux * (.5 - EDGE_MARGIN) / Math.max(Math.abs(ux), Math.abs(uz));
                double edgeZ = under.getZ() + .5 + uz * (.5 - EDGE_MARGIN) / Math.max(Math.abs(ux), Math.abs(uz));
                double remain = (edgeX - pos.x) * ux + (edgeZ - pos.z) * uz;
                boolean sprint = dist > 2.65 || target.getY() > under.getY();
                look(mc, target.getX() + .5, target.getZ() + .5);
                // Jump while still supported by the departure block, retaining the short approach velocity.
                boolean jump = remain <= Math.max(.10, mc.player.getDeltaMovement().horizontalDistance() * 1.35);
                steer(mc, ux, uz, sprint, jump);
                if (jump) { jumpLatch = true; transition(State.FLIGHT); }
            }
            case FLIGHT -> {
                if (target == null) { reset(mc); return; }
                Vec3 pos = mc.player.position();
                double dx = target.getX() + .5 - pos.x, dz = target.getZ() + .5 - pos.z;
                look(mc, target.getX() + .5, target.getZ() + .5);
                // Gentle mid-air correction; stop accelerating once within the landing footprint.
                if (Math.abs(dx) < .23 && Math.abs(dz) < .23) release(mc);
                else steer(mc, dx, dz, false, false);
                if (mc.player.onGround() && phaseTicks > 2) { release(mc); transition(State.LAND); }
            }
            case LAND -> {
                release(mc);
                if (phaseTicks >= jumpDelayTicks) transition(State.IDLE);
            }
        }
    }
    private void showDelay(Minecraft mc) {
        if (mc.player != null) mc.player.displayClientMessage(
            Component.literal("SmartParkour: задержка " + jumpDelayTicks + " тиков (" + (jumpDelayTicks * 50) + " мс)"), true);
    }
    private BlockPos findTarget(Minecraft mc) {
        BlockPos current = mc.player.blockPosition().below();
        BlockPos best = null;
        double score = Double.POSITIVE_INFINITY;
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++)
            for (int y = -1; y <= 1; y++) {
                BlockPos p = current.offset(x, y, z);
                if (p.equals(current) || !isTarget(mc, p)) continue;
                double horizontal = Math.hypot(x, z);
                if (horizontal < 1.3 || horizontal > 5.2) continue;
                double s = horizontal + Math.abs(y) * .3;
                if (s < score) { score = s; best = p; }
            }
        return best;
    }
    private boolean isTarget(Minecraft mc, BlockPos p) {
        return mc.level.getBlockState(p).is(Blocks.GRAY_WOOL) && mc.level.getBlockState(p.above()).isAir() && mc.level.getBlockState(p.above(2)).isAir();
    }
    private void look(Minecraft mc, double x, double z) {
        Vec3 pos = mc.player.position();
        mc.player.setYRot((float)(Math.toDegrees(Math.atan2(-(x - pos.x), z - pos.z))));
    }
    private void steer(Minecraft mc, double dx, double dz, boolean sprint, boolean jump) {
        // No KeyMapping.setDown: prevents the 0.7 stuck-key bug when disabling.
        double yaw = Math.toRadians(mc.player.getYRot());
        double forward = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
        double right = Math.cos(yaw) * dx + Math.sin(yaw) * dz;
        mc.options.keyUp.setDown(forward > .08);
        mc.options.keyDown.setDown(forward < -.08);
        mc.options.keyRight.setDown(right > .08);
        mc.options.keyLeft.setDown(right < -.08);
        mc.options.keySprint.setDown(sprint);
        mc.options.keyJump.setDown(jump);
    }
    private void release(Minecraft mc) {
        if (mc.options == null) return;
        // Release only the keys controlled by the mod. Vanilla input polling
        // resumes on the next client tick; never keep simulated keys held.
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        jumpLatch = false;
    }
    private void reset(Minecraft mc) { release(mc); state = State.IDLE; target = null; phaseTicks = 0; }
    private void transition(State next) { state = next; phaseTicks = 0; }
    private final class SettingsScreen extends Screen {
        SettingsScreen() { super(Component.literal("SmartParkour — настройки")); }

        @Override protected void init() {
            int center = this.width / 2;
            int y = this.height / 2 - 25;
            addRenderableWidget(new AbstractSliderButton(center - 110, y, 220, 20,
                    Component.empty(), jumpDelayTicks / 100.0) {
                { updateMessage(); }
                @Override protected void updateMessage() {
                    setMessage(Component.literal("Задержка: " + jumpDelayTicks * 50 + " мс"));
                }
                @Override protected void applyValue() {
                    jumpDelayTicks = Math.max(0, Math.min(100, (int) Math.round(this.value * 100)));
                    updateMessage();
                }
            });
            addRenderableWidget(Button.builder(Component.literal("Готово"),
                    button -> onClose()).bounds(center - 100, y + 32, 200, 20).build());
        }

        @Override public void onClose() { Minecraft.getInstance().setScreen(null); }
        @Override public boolean isPauseScreen() { return false; }
    }

}
