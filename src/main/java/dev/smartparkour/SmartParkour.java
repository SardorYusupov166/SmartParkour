package dev.smartparkour;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.io.StringReader;
import java.io.StringWriter;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;

/** Fresh 0.8 controller. No teleportation, no persistent key overrides. */
public final class SmartParkour implements ClientModInitializer {
    private enum State { IDLE, ROTATE, VERIFY, ALIGN, APPROACH, FLIGHT, CENTER, LAND, RETURN_ROTATE, RETURN_MOVE }
    private boolean landingSneak;
    private BlockPos home = new BlockPos(-65, 84, 306);
    private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("smartparkour.properties");
    private static final int NO_TARGET_TICKS = 12;
    private int missingTargetTicks;
    private int homeFacingTicks;
    private boolean returningHome;
    private static final double EDGE_MARGIN = 0.13;
    private static final double CENTER_TOLERANCE = 0.075;
    private static final int SEARCH_RADIUS = 6;
    private static final double YAW_TOLERANCE_DEGREES = 2.0;
    private int facingTicks;
    private KeyMapping toggle;
    private KeyMapping settingsKey;
    // 20 client ticks = approximately one second at normal TPS.
    private int jumpDelayTicks = 10;
    private boolean enabled;
    private State state = State.IDLE;
    private BlockPos target;
    private BlockPos departure;
    private BlockPos previousPlatform;
    private boolean airborneSeen;
    private int phaseTicks;
    private boolean jumpLatch;
    private boolean toggleWasDown;
    private BlockPos landingPlatform;
    private int centeredTicks;

    @Override public void onInitializeClient() {
        loadConfig();
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
            previousPlatform = null;
            missingTargetTicks = 0;
            returningHome = false;
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("SmartParkour 0.9.1: " + (enabled ? "ON" : "OFF")), true);
        }
        toggleWasDown = down;
        if (!enabled || mc.player == null || mc.level == null) return;
        if (mc.screen != null) { release(mc); return; }
        // A jump target is immutable until landing, failure or explicit cancellation.
        if (!mc.player.onGround() && state != State.FLIGHT && state != State.LAND && state != State.CENTER) {
            if (target != null) transition(State.FLIGHT);
            else { release(mc); return; }
        }
        if (++phaseTicks > 120 && state != State.RETURN_ROTATE && state != State.RETURN_MOVE) { reset(mc); return; }
        // Do not interrupt an airborne jump or the landing/centering sequence.
        if (state == State.IDLE && mc.player.onGround()) {
            BlockPos standing = mc.player.blockPosition().below();
            if (findTarget(mc) == null) {
                if (++missingTargetTicks >= NO_TARGET_TICKS) {
                    if (!returningHome) mc.player.displayClientMessage(Component.literal("SmartParkour: возврат к " + home.getX() + " " + home.getY() + " " + home.getZ()), true);
                    returningHome = true;
                    target = null;
                    departure = null;
                    transition(State.RETURN_ROTATE);
                }
            } else {
                missingTargetTicks = 0;
                returningHome = false;
            }
        }
        switch (state) {
            case IDLE -> {
                if (!mc.player.onGround()) return;
                BlockPos standing = mc.player.blockPosition().below();
                if (!isTarget(mc, standing)) { release(mc); return; }
                target = findTarget(mc);
                if (target != null) { departure = standing.immutable(); transition(State.ROTATE); }
            }
            case ROTATE -> {
                release(mc);
                if (target == null || departure == null || !isTarget(mc, target) || !mc.player.onGround()) { reset(mc); return; }
                look(mc, target.getX() + .5, target.getZ() + .5);
                transition(State.VERIFY);
            }
            case VERIFY -> {
                release(mc);
                if (target == null || departure == null || !isTarget(mc, target) || !mc.player.onGround()) { reset(mc); return; }
                if (!isFacing(mc, target)) {
                    look(mc, target.getX() + .5, target.getZ() + .5);
                    facingTicks = 0;
                    return;
                }
                if (++facingTicks >= 2) transition(State.ALIGN);
            }
            case ALIGN -> {
                if (target == null || !isTarget(mc, target) || !mc.player.onGround()) { reset(mc); return; }
                Vec3 pos = mc.player.position();
                BlockPos under = departure;
                double cx = under.getX() + .5, cz = under.getZ() + .5;
                if (!isFacing(mc, target)) { release(mc); transition(State.ROTATE); return; }
                if (Math.hypot(cx - pos.x, cz - pos.z) > CENTER_TOLERANCE) {
                    steer(mc, cx - pos.x, cz - pos.z, false, false);
                } else { release(mc); transition(State.APPROACH); }
            }
            case APPROACH -> {
                if (target == null || !isTarget(mc, target) || !mc.player.onGround()) { reset(mc); return; }
                Vec3 pos = mc.player.position();
                BlockPos under = departure;
                double dx = target.getX() + .5 - (under.getX() + .5);
                double dz = target.getZ() + .5 - (under.getZ() + .5);
                double dist = Math.hypot(dx, dz);
                if (dist < .001) { reset(mc); return; }
                double ux = dx / dist, uz = dz / dist;
                double edgeX = under.getX() + .5 + ux * (.5 - EDGE_MARGIN) / Math.max(Math.abs(ux), Math.abs(uz));
                double edgeZ = under.getZ() + .5 + uz * (.5 - EDGE_MARGIN) / Math.max(Math.abs(ux), Math.abs(uz));
                double remain = (edgeX - pos.x) * ux + (edgeZ - pos.z) * uz;
                boolean sprint = dist > 2.65 || target.getY() > under.getY();
                if (!isFacing(mc, target)) { release(mc); transition(State.ROTATE); return; }
                // Jump while still supported by the departure block, retaining the short approach velocity.
                boolean jump = remain <= Math.max(.10, mc.player.getDeltaMovement().horizontalDistance() * 1.35);
                steer(mc, ux, uz, sprint, jump);
                if (jump) { jumpLatch = true; transition(State.FLIGHT); }
            }
            case FLIGHT -> {
                if (target == null) { reset(mc); return; }
                if (!mc.player.onGround()) airborneSeen = true;
                Vec3 pos = mc.player.position();
                double dx = target.getX() + .5 - pos.x, dz = target.getZ() + .5 - pos.z;
                look(mc, target.getX() + .5, target.getZ() + .5);
                // Only engage sneak when the player's FEET are horizontally
                // inside the target block and the player is descending above it.
                // Do not use the expanded 0.6-wide collision box here: overlapping
                // the edge is not enough to guarantee a safe landing.
                double minX = target.getX() + 0.03;
                double maxX = target.getX() + 0.97;
                double minZ = target.getZ() + 0.03;
                double maxZ = target.getZ() + 0.97;
                boolean feetInside = pos.x >= minX && pos.x <= maxX
                        && pos.z >= minZ && pos.z <= maxZ;
                double surfaceY = target.getY() + 1.0;
                boolean descending = mc.player.getDeltaMovement().y <= 0.0;
                boolean aboveSurface = pos.y >= surfaceY - 0.001;
                landingSneak = feetInside && descending && aboveSurface;

                // Do not keep accelerating past the target center. Sneak is not
                // an air brake, so release forward motion and steer against drift.
                Vec3 velocity = mc.player.getDeltaMovement();
                double desiredX = dx * 0.36 - velocity.x * 2.0;
                double desiredZ = dz * 0.36 - velocity.z * 2.0;
                if (feetInside && Math.hypot(dx, dz) < 0.19) {
                    releaseMovement(mc);
                } else if (Math.hypot(desiredX, desiredZ) < 0.025) {
                    releaseMovement(mc);
                } else {
                    steer(mc, desiredX, desiredZ, false, false);
                }
                mc.options.keyShift.setDown(landingSneak);

                // The block's integer Y is its bottom. Player feet must be at
                // blockY + 1.0, not at a fractional Y while still falling.
                boolean landedAtSurface = mc.player.onGround()
                        && Math.abs(pos.y - surfaceY) <= 0.001
                        && feetInside;
                if (landedAtSurface && airborneSeen) {
                    releaseMovement(mc);
                    previousPlatform = departure;
                    landingPlatform = target.immutable();
                    departure = null;
                    target = null;
                    centeredTicks = 0;
                    landingSneak = true;
                    mc.options.keyShift.setDown(true);
                    transition(State.CENTER);
                } else if (mc.player.onGround() && airborneSeen && !landedAtSurface) {
                    // Grounded somewhere else: abort instead of selecting a
                    // second target or treating an edge collision as success.
                    reset(mc);
                }
            }
            case CENTER -> {
                // Never select a new platform until centered on the one just landed on.
                if (landingPlatform == null || !mc.player.onGround() ||
                    Math.abs(mc.player.getY() - (landingPlatform.getY() + 1.0)) > 0.001 ||
                    mc.player.getX() < landingPlatform.getX() || mc.player.getX() >= landingPlatform.getX() + 1 ||
                    mc.player.getZ() < landingPlatform.getZ() || mc.player.getZ() >= landingPlatform.getZ() + 1) {
                    reset(mc);
                    return;
                }
                double cx = landingPlatform.getX() + 0.5;
                double cz = landingPlatform.getZ() + 0.5;
                Vec3 pos = mc.player.position();
                Vec3 vel = mc.player.getDeltaMovement();
                double ex = cx - pos.x, ez = cz - pos.z;
                double distance = Math.hypot(ex, ez);
                double speed = Math.hypot(vel.x, vel.z);
                // Brake residual momentum instead of oscillating around the center.
                if (distance <= CENTER_TOLERANCE && speed < 0.035) {
                    releaseMovement(mc);
                    if (++centeredTicks >= 3) {
                        landingSneak = false;
                        mc.options.keyShift.setDown(false);
                        landingPlatform = null;
                        transition(State.LAND);
                    } else mc.options.keyShift.setDown(true);
                } else {
                    centeredTicks = 0;
                    // Predict a few ticks ahead to begin braking before reaching center.
                    double desiredX = ex * 0.35 - vel.x * 1.8;
                    double desiredZ = ez * 0.35 - vel.z * 1.8;
                    if (Math.hypot(desiredX, desiredZ) < 0.012) releaseMovement(mc);
                    else steer(mc, desiredX, desiredZ, false, false);
                    landingSneak = true;
                    mc.options.keyShift.setDown(true);
                }
            }
            case RETURN_ROTATE -> {
                release(mc);
                if (homeReached(mc)) { homeComplete(mc); return; }
                if (findTarget(mc) != null && isTarget(mc, mc.player.blockPosition().below())) {
                    missingTargetTicks = 0; returningHome = false; transition(State.IDLE); return;
                }
                if (!mc.player.onGround()) return;
                look(mc, home.getX() + .5, home.getZ() + .5);
                if (isFacingPoint(mc, home.getX() + .5, home.getZ() + .5)) {
                    if (++homeFacingTicks >= 2) transition(State.RETURN_MOVE);
                } else homeFacingTicks = 0;
            }
            case RETURN_MOVE -> {
                release(mc);
                if (homeReached(mc)) { homeComplete(mc); return; }
                if (findTarget(mc) != null && isTarget(mc, mc.player.blockPosition().below())) {
                    missingTargetTicks = 0; returningHome = false; transition(State.IDLE); return;
                }
                if (!mc.player.onGround()) { transition(State.RETURN_ROTATE); return; }
                if (!isFacingPoint(mc, home.getX() + .5, home.getZ() + .5)) {
                    transition(State.RETURN_ROTATE); return;
                }
                Vec3 pos = mc.player.position();
                double dx = home.getX() + .5 - pos.x;
                double dz = home.getZ() + .5 - pos.z;
                double dist = Math.hypot(dx, dz);
                if (dist < .001) { homeComplete(mc); return; }
                double ux = dx / dist, uz = dz / dist;
                // Refuse to blindly walk into a void. Navigation around obstacles
                // requires a separate pathfinder and is deliberately not faked here.
                BlockPos ahead = BlockPos.containing(pos.x + ux * .65, pos.y - .2, pos.z + uz * .65).below();
                if (mc.level.getBlockState(ahead).isAir()) {
                    release(mc);
                    if (phaseTicks % 40 == 0) mc.player.displayClientMessage(Component.literal("SmartParkour: путь к старту прерван — впереди пустота"), true);
                    return;
                }
                steer(mc, dx, dz, false, false);
            }
            case LAND -> {
                release(mc);
                if (phaseTicks >= jumpDelayTicks) transition(State.IDLE);
            }
        }
    }
    private boolean homeReached(Minecraft mc) {
        Vec3 pos = mc.player.position();
        return mc.player.onGround() && Math.abs(pos.x - (home.getX() + .5)) < .20
            && Math.abs(pos.z - (home.getZ() + .5)) < .20
            && Math.abs(pos.y - home.getY()) < .12;
    }
    private void homeComplete(Minecraft mc) {
        release(mc);
        returningHome = false;
        missingTargetTicks = 0;
        transition(State.IDLE);
    }
    private boolean isFacingPoint(Minecraft mc, double x, double z) {
        Vec3 pos = mc.player.position();
        double desired = Math.toDegrees(Math.atan2(-(x - pos.x), z - pos.z));
        double error = ((mc.player.getYRot() - desired + 540.0) % 360.0) - 180.0;
        return Math.abs(error) <= YAW_TOLERANCE_DEGREES;
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
                if (p.equals(current) || p.equals(previousPlatform) || !isTarget(mc, p)) continue;
                double horizontal = Math.hypot(x, z);
                if (horizontal < 1.3 || horizontal > 5.2) continue;
                double s = horizontal + Math.abs(y) * .3;
                // Stable tie-break: coordinates, independent of iteration timing.
                if (s < score - 1e-7 || (Math.abs(s - score) < 1e-7 &&
                    (best == null || p.getX() < best.getX() ||
                    (p.getX() == best.getX() && (p.getZ() < best.getZ() ||
                    (p.getZ() == best.getZ() && p.getY() < best.getY())))))) {
                    score = s; best = p.immutable();
                }
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
    private boolean isFacing(Minecraft mc, BlockPos block) {
        Vec3 pos = mc.player.position();
        double desired = Math.toDegrees(Math.atan2(-(block.getX() + .5 - pos.x), block.getZ() + .5 - pos.z));
        double error = ((mc.player.getYRot() - desired + 540.0) % 360.0) - 180.0;
        return Math.abs(error) <= YAW_TOLERANCE_DEGREES;
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
    private void releaseMovement(Minecraft mc) {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
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
        mc.options.keyShift.setDown(false);
        landingSneak = false;
        jumpLatch = false;
    }
    private void reset(Minecraft mc) { release(mc); returningHome = false; missingTargetTicks = 0; state = State.IDLE; target = null; departure = null; landingPlatform = null; centeredTicks = 0; facingTicks = 0; airborneSeen = false; phaseTicks = 0; }
    private void transition(State next) { state = next; phaseTicks = 0; if (next == State.ROTATE) facingTicks = 0; if (next == State.FLIGHT) { airborneSeen = false; landingSneak = false; } }
    private void loadConfig() {
        if (!Files.isRegularFile(configPath)) return;
        try {
            Properties props = new Properties();
            props.load(new StringReader(Files.readString(configPath, StandardCharsets.UTF_8)));
            int x = Integer.parseInt(props.getProperty("homeX", "-65"));
            int y = Integer.parseInt(props.getProperty("homeY", "84"));
            int z = Integer.parseInt(props.getProperty("homeZ", "306"));
            home = new BlockPos(x, y, z);
            jumpDelayTicks = Math.max(0, Math.min(100, Integer.parseInt(props.getProperty("delayTicks", "10"))));
        } catch (Exception ex) {
            System.err.println("SmartParkour: cannot read config: " + ex.getMessage());
        }
    }
    private void saveConfig() {
        try {
            Files.createDirectories(configPath.getParent());
            Properties props = new Properties();
            props.setProperty("homeX", Integer.toString(home.getX()));
            props.setProperty("homeY", Integer.toString(home.getY()));
            props.setProperty("homeZ", Integer.toString(home.getZ()));
            props.setProperty("delayTicks", Integer.toString(jumpDelayTicks));
            StringWriter writer = new StringWriter();
            props.store(writer, "SmartParkour settings");
            Files.writeString(configPath, writer.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            System.err.println("SmartParkour: cannot save config: " + ex.getMessage());
        }
    }
    private final class SettingsScreen extends Screen {
        private EditBox xField, yField, zField;
        private Component error = Component.empty();
        SettingsScreen() { super(Component.literal("SmartParkour — настройки")); }
        @Override protected void init() {
            int center = this.width / 2;
            int y = this.height / 2 - 72;
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
            y += 38;
            xField = new EditBox(this.font, center - 110, y, 68, 20, Component.literal("X"));
            yField = new EditBox(this.font, center - 34, y, 68, 20, Component.literal("Y"));
            zField = new EditBox(this.font, center + 42, y, 68, 20, Component.literal("Z"));
            xField.setValue(Integer.toString(home.getX()));
            yField.setValue(Integer.toString(home.getY()));
            zField.setValue(Integer.toString(home.getZ()));
            addRenderableWidget(xField);
            addRenderableWidget(yField);
            addRenderableWidget(zField);
            addRenderableWidget(Button.builder(Component.literal("Сохранить"), button -> saveAndClose())
                    .bounds(center - 110, y + 38, 220, 20).build());
        }
        private void saveAndClose() {
            try {
                int x = Integer.parseInt(xField.getValue().trim());
                int y = Integer.parseInt(yField.getValue().trim());
                int z = Integer.parseInt(zField.getValue().trim());
                if (y < -64 || y > 320) {
                    error = Component.literal("Y должен быть от -64 до 320");
                    return;
                }
                home = new BlockPos(x, y, z);
                saveConfig();
                reset(Minecraft.getInstance());
                onClose();
            } catch (NumberFormatException ex) {
                error = Component.literal("Введите целые координаты X, Y, Z");
            }
        }
        @Override public void onClose() { Minecraft.getInstance().setScreen(null); }
        @Override public boolean isPauseScreen() { return false; }
    }
}
