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
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.io.StringReader;
import java.io.StringWriter;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;

/** SmartParkour controller with cautious, balanced and experimental teleport modes. */
public final class SmartParkour implements ClientModInitializer {
    private enum MovementMode { CAUTIOUS, BALANCED, CHEAT }
    private MovementMode movementMode = MovementMode.CAUTIOUS;
    private enum CheatTeleportTrigger { NEW_BLOCK, ON_LANDING }
    private CheatTeleportTrigger cheatTeleportTrigger = CheatTeleportTrigger.ON_LANDING;
    private final Set<BlockPos> cheatVisibleBeforeTeleport = new HashSet<>();
    private final Set<BlockPos> cheatKnownPlatforms = new HashSet<>();
    private final Deque<BlockPos> cheatQueue = new ArrayDeque<>();
    private boolean cheatAwaitingSignal;
    private BlockPos queuedNextTarget;
    private BlockPos cheatExpectedLanding;
    private Vec3 cheatRequestedPosition;
    private static final int CHEAT_SETTLE_TIMEOUT_TICKS = 35;
    private enum ReturnMethod { TELEPORT, WALK }
    private ReturnMethod returnMethod = ReturnMethod.TELEPORT;
    private int emptyScanAttempts;
    private int emptyScanInterval;
    private static final int EMPTY_SCAN_COUNT = 3;
    private static final int EMPTY_SCAN_INTERVAL_TICKS = 5;
    private enum State { IDLE, RETURN_HOME, ROTATE, VERIFY, ALIGN, APPROACH, FLIGHT, CENTER, LAND, CHEAT_SETTLE, RETURN_WARP, WALK_TO_PLATE, WAIT_FOR_START }
    private boolean landingSneak;
    private BlockPos home = new BlockPos(-65, 84, 306);
    private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("smartparkour.properties");
    private int noTargetTicks = 12;
    private int missingTargetTicks;
    private int homeFacingTicks;
    private boolean returningHome;
    private Vec3 warpOrigin;
    private boolean warpCommandSent;
    private int warpCooldownTicks;
    private int warpWaitTicks = 160;
    private int walkToPlateTimeoutTicks = 240;
    private int startWaitTicks = 100;
    private Vec3 lastPlatePosition;
    private int plateStuckTicks;
    private boolean warpDetected;
    private static final double EDGE_MARGIN = 0.13;
    // If a target is close enough to collide with the approach, jump from center.
    private static final double CLOSE_JUMP_DISTANCE = 2.05;
    private int approachStallLimitTicks = 5;
    private int approachStallTicks;
    private Vec3 lastApproachPosition;
    private int centerToleranceXPixels = 3;
    private int centerToleranceZPixels = 3;
    private boolean exactCenter = false;
    private static final double LANDING_Y_TOLERANCE = 0.1;
    private static final int SEARCH_RADIUS = 6;
    private static final double YAW_TOLERANCE_DEGREES = 2.0;
    private int facingTicks;
    private KeyMapping toggle;
    private KeyMapping settingsKey;
    // 20 client ticks = approximately one second at normal TPS.
    private int jumpDelayTicks = 2;
    private boolean enabled;
    private State state = State.IDLE;
    private BlockPos target;
    private BlockPos departure;
    private BlockPos previousPlatform;
    // One-time detour: origin -> intermediate -> original -> intermediate.
    private BlockPos routeOriginal;
    private BlockPos routeIntermediate;
    private int routeStep; // 0: no detour, 1: go original, 2: revisit intermediate

    private boolean airborneSeen;
    private int phaseTicks;
    private int phaseTimeoutTicks = 120;
    private int facingConfirmTicks = 1;
    private int homeFacingConfirmTicks = 2;
    private boolean jumpLatch;
    private boolean toggleWasDown;
    private BlockPos landingPlatform;
    private int centeredTicks;
    private int centerTimeoutTicks = 12;
    private int inactivityTimeoutTicks = 100;
    private boolean inactivityRescue = false;
    private static volatile boolean continueInMenus = false;
    public static boolean shouldContinueInMenus() { return continueInMenus; }
    // Progress watchdog: state changes and tiny position adjustments are NOT progress.
    private int inactivityTicks;
    private int inactivityRecoveryAttempts;
    private BlockPos lastWatchPlatform;
    private BlockPos lastWatchTarget;
    private int lastWatchQueueSize = -1;
    private int startWaitCycles;
    private static final int MAX_REPLAN_ATTEMPTS = 2;

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
            clearRoute();
            missingTargetTicks = 0;
            returningHome = false;
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("SmartParkour 1.0.1: " + (enabled ? "ON" : "OFF")), true);
        }
        toggleWasDown = down;
        if (!enabled || mc.player == null || mc.level == null) return;
        if (warpCooldownTicks > 0) warpCooldownTicks--;
        if (mc.screen != null && !continueInMenus) { release(mc); clearInactivityWatch(); return; }
        if (inactivityRescue && warpCooldownTicks == 0 && checkInactivity(mc)) {
            release(mc);
            if (state == State.WALK_TO_PLATE || state == State.WAIT_FOR_START || state == State.RETURN_HOME || state == State.RETURN_WARP) {
                mc.player.displayClientMessage(Component.literal("SmartParkour: возврат завис, повторная попытка"), true);
                reset(mc);
                returningHome = true;
                transition(State.RETURN_HOME);
                warpCooldownTicks = 40;
                return;
            }
            BlockPos here = mc.player.blockPosition().below();
            BlockPos alternative = mc.player.onGround() && isTarget(mc, here) ? findTarget(mc) : null;
            if (alternative != null && inactivityRecoveryAttempts < MAX_REPLAN_ATTEMPTS) {
                inactivityRecoveryAttempts++;
                reset(mc);
                target = alternative;
                departure = here.immutable();
                transition(State.ROTATE);
                mc.player.displayClientMessage(Component.literal("SmartParkour: нет прогресса, перепланирование " + inactivityRecoveryAttempts + "/" + MAX_REPLAN_ATTEMPTS), true);
            } else {
                reset(mc);
                returningHome = true;
                transition(State.RETURN_HOME);
                mc.player.displayClientMessage(Component.literal("SmartParkour: прогресс отсутствует, возвращаюсь к точке"), true);
            }
            return;
        }
        // A jump target is immutable until landing, failure or explicit cancellation.
        if (movementMode != MovementMode.CHEAT && !mc.player.onGround() && state != State.FLIGHT && state != State.LAND && state != State.CENTER && state != State.CHEAT_SETTLE) {
            if (target != null) transition(State.FLIGHT);
            else { release(mc); return; }
        }
        if (++phaseTicks > phaseTimeoutTicks && state != State.CHEAT_SETTLE && state != State.RETURN_WARP && state != State.WALK_TO_PLATE && state != State.WAIT_FOR_START && state != State.IDLE) {
            release(mc);
            if (inactivityRescue) {
                reset(mc);
                returningHome = true;
                transition(State.RETURN_HOME);
                mc.player.displayClientMessage(Component.literal("SmartParkour: тайм-аут этапа, восстановление"), true);
            } else reset(mc);
            return;
        }
        // Do not interrupt an airborne jump or the landing/centering sequence.
        if (state == State.IDLE && mc.player.onGround()) {
            BlockPos standing = mc.player.blockPosition().below();
            if (findTarget(mc) == null) {
                // Three independent scans separated by a few client ticks.
                // A platform seen on any scan cancels recovery.
                if (warpCooldownTicks == 0 && ++emptyScanInterval >= EMPTY_SCAN_INTERVAL_TICKS) {
                    emptyScanInterval = 0;
                    if (++emptyScanAttempts >= EMPTY_SCAN_COUNT) {
                        emptyScanAttempts = 0;
                        returningHome = true;
                        target = null;
                        departure = null;
                        transition(State.RETURN_HOME);
                        mc.player.displayClientMessage(Component.literal("SmartParkour: платформ нет после 3 проверок, возвращаюсь"), true);
                    }
                }
            } else {
                emptyScanAttempts = 0;
                emptyScanInterval = 0;
                missingTargetTicks = 0;
                returningHome = false;
            }
        }
        switch (state) {
            case IDLE -> {
                if (!mc.player.onGround()) return;
                BlockPos standing = mc.player.blockPosition().below();
                if (!isTarget(mc, standing)) { release(mc); return; }
                if (routeStep == 1 && isTarget(mc, routeOriginal) && !standing.equals(routeOriginal)) {
                    target = routeOriginal;
                } else if (routeStep == 3 && isTarget(mc, routeIntermediate) && !standing.equals(routeIntermediate)) {
                    target = routeIntermediate;
                } else {
                    if (routeStep != 0) clearRoute();
                    target = findTarget(mc);
                }
                if (target != null) { departure = standing.immutable(); transition(State.ROTATE); }
            }
            case ROTATE -> {
                release(mc);
                if (target == null || departure == null || !isTarget(mc, target) || !mc.player.onGround()) { reset(mc); return; }
                if (movementMode != MovementMode.CHEAT && rerouteThroughIntermediate(mc)) return;
                if (movementMode == MovementMode.CHEAT) {
                    // Feet are placed half a block ABOVE the top surface of the target.
                    // This is a client-side position change; servers may reject it.
                    BlockPos destination = target.immutable();
                    BlockPos source = departure.immutable();
                    release(mc);
                    if (cheatTeleportTrigger == CheatTeleportTrigger.NEW_BLOCK) {
                        // Seed existing platforms as already known; they are not confirmations.
                        if (cheatKnownPlatforms.isEmpty()) snapshotCheatTargets(mc, source);
                        cheatQueue.remove(destination);
                    }
                    mc.player.setDeltaMovement(Vec3.ZERO);
                    mc.player.setPos(destination.getX() + 0.5, destination.getY() + 1.5, destination.getZ() + 0.5);
                    previousPlatform = source;
                    cheatExpectedLanding = destination;
                    cheatRequestedPosition = new Vec3(destination.getX() + .5, destination.getY() + 1.5, destination.getZ() + .5);
                    target = null;
                    departure = null;
                    landingPlatform = null;
                    clearRoute();
                    cheatAwaitingSignal = cheatTeleportTrigger == CheatTeleportTrigger.NEW_BLOCK;
                    transition(State.CHEAT_SETTLE);
                    return;
                }
                look(mc, target.getX() + .5, target.getZ() + .5);
                transition(State.VERIFY);
            }
            case VERIFY -> {
                release(mc);
                if (target == null || departure == null || !isTarget(mc, target) || !mc.player.onGround()) { reset(mc); return; }
                if (movementMode != MovementMode.CHEAT && rerouteThroughIntermediate(mc)) return;
                if (!isFacing(mc, target)) {
                    look(mc, target.getX() + .5, target.getZ() + .5);
                    facingTicks = 0;
                    return;
                }
                if (++facingTicks >= facingConfirmTicks) transition(State.ALIGN);
            }
            case ALIGN -> {
                if (target == null || !isTarget(mc, target) || !mc.player.onGround()) { reset(mc); return; }
                if (movementMode != MovementMode.CHEAT && rerouteThroughIntermediate(mc)) return;
                Vec3 pos = mc.player.position();
                BlockPos under = departure;
                double cx = under.getX() + .5, cz = under.getZ() + .5;
                if (!isFacing(mc, target)) { release(mc); transition(State.ROTATE); return; }
                boolean balancedTakeoff = movementMode == MovementMode.BALANCED
                        && Math.abs(pos.x - cx) <= 0.30 && Math.abs(pos.z - cz) <= 0.30
                        && mc.player.getDeltaMovement().horizontalDistance() < 0.13;
                if (!withinCenter(pos.x, pos.z, under) && !balancedTakeoff) {
                    steer(mc, cx - pos.x, cz - pos.z, false, false);
                } else { release(mc); transition(State.APPROACH); }
            }
            case APPROACH -> {
                if (target == null || !isTarget(mc, target) || !mc.player.onGround()) { reset(mc); return; }
                if (movementMode != MovementMode.CHEAT && rerouteThroughIntermediate(mc)) return;
                Vec3 pos = mc.player.position();
                BlockPos under = departure;
                double dx = target.getX() + .5 - (under.getX() + .5);
                double dz = target.getZ() + .5 - (under.getZ() + .5);
                double dist = Math.hypot(dx, dz);
                if (dist < .001) { reset(mc); return; }
                double ux = dx / dist, uz = dz / dist;
                if (!isFacing(mc, target)) { release(mc); transition(State.ROTATE); return; }
                // Never insist on walking to the edge when the next block is close.
                // A nearby or elevated block can physically obstruct that approach.
                double travel = Math.max(0.0, Math.min(.5 - EDGE_MARGIN,
                        (dist - 1.45) * 0.65));
                double edgeX = under.getX() + .5 + ux * travel / Math.max(Math.abs(ux), Math.abs(uz));
                double edgeZ = under.getZ() + .5 + uz * travel / Math.max(Math.abs(ux), Math.abs(uz));
                double remain = (edgeX - pos.x) * ux + (edgeZ - pos.z) * uz;
                // A two-block center-to-center jump needs sprint momentum and a
                // controlled takeoff closer to the edge, not a standing jump.
                boolean longJump = dist >= 1.75;
                boolean sprint = longJump || target.getY() > under.getY();
                if (movementMode == MovementMode.BALANCED && dist >= 1.55) sprint = true;
                // If movement is blocked for several ticks, jump rather than stall.
                if (lastApproachPosition != null &&
                        Math.hypot(pos.x - lastApproachPosition.x, pos.z - lastApproachPosition.z) < 0.012)
                    approachStallTicks++;
                else approachStallTicks = 0;
                lastApproachPosition = pos;
                boolean closeJump = dist < 1.75;
                // For longer jumps, build horizontal velocity before takeoff.
                double horizontalSpeed = mc.player.getDeltaMovement().horizontalDistance();
                boolean readyToJump = !longJump || horizontalSpeed >= 0.11 || maxApproachDisplacement(pos, under) >= 0.39;
                boolean jump = (closeJump && (remain <= 0.16 || approachStallTicks >= approachStallLimitTicks)) || (readyToJump && approachStallTicks >= approachStallLimitTicks) ||
                        (readyToJump &&
                        remain <= Math.max(.10, horizontalSpeed * 1.35));
                // Do not walk off the departure block while waiting for takeoff.
                // At the edge, force the takeoff on this tick.
                double maxLocal = Math.max(Math.abs(pos.x - (under.getX() + .5)),
                                           Math.abs(pos.z - (under.getZ() + .5)));
                if (maxLocal >= .40) jump = true;
                if (movementMode == MovementMode.BALANCED && !jump && longJump
                        && horizontalSpeed >= 0.16 && remain <= 0.24) jump = true;
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
                double surfaceY = target.getY() + 1.0; // Target block top Y, not a rounded player Y
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
                // Confirm landing against the TARGET block's exact top surface.
                // Never use rounding of the player's Y (e.g. 56.8 -> 56).
                boolean landedAtSurface = mc.player.onGround()
                        && Math.abs(mc.player.getY() - (target.getY() + 1.0)) <= LANDING_Y_TOLERANCE
                        && feetInside
                        && isTarget(mc, target);
                if (landedAtSurface && airborneSeen) {
                    releaseMovement(mc);
                    previousPlatform = departure;
                    if (routeStep == 1 && target.equals(routeIntermediate)) routeStep = 2;
                    else if (routeStep == 2 && target.equals(routeOriginal)) routeStep = 3;
                    else if (routeStep == 3 && target.equals(routeIntermediate)) clearRoute();
                    landingPlatform = target.immutable();
                    departure = null;
                    target = null;
                    centeredTicks = 0;
                    // Landing is confirmed: release sneak IMMEDIATELY.
                    landingSneak = false;
                    mc.options.keyShift.setDown(false);
                    if (movementMode == MovementMode.BALANCED) {
                        // Landing hand-off: avoid centering only when there is a stable,
                        // comfortably interior landing. Otherwise use cautious CENTER.
                        Vec3 landedVelocity = mc.player.getDeltaMovement();
                        double lx = pos.x - (landingPlatform.getX() + 0.5);
                        double lz = pos.z - (landingPlatform.getZ() + 0.5);
                        if (Math.abs(lx) < 0.28 && Math.abs(lz) < 0.28
                                && landedVelocity.horizontalDistance() < 0.095) {
                            landingPlatform = null;
                            transition(State.LAND);
                        } else transition(State.CENTER);
                    } else transition(State.CENTER);
                } else if (mc.player.onGround() && airborneSeen && !landedAtSurface) {
                    // Grounded somewhere else: abort instead of selecting a
                    // second target or treating an edge collision as success.
                    reset(mc);
                }
            }
            case CENTER -> {
                // Centering is best-effort. After 20 client ticks, continue from
                // the actual landing position instead of getting stuck forever.
                if (phaseTicks >= centerTimeoutTicks) {
                    releaseMovement(mc);
                    landingSneak = false;
                    mc.options.keyShift.setDown(false);
                    landingPlatform = null;
                    centeredTicks = 0;
                    transition(State.LAND);
                    return;
                }
                // Never select a new platform until centered on the one just landed on.
                if (landingPlatform == null || !mc.player.onGround() ||
                    Math.abs(mc.player.getY() - (landingPlatform.getY() + 1.0)) > LANDING_Y_TOLERANCE ||
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
                if (withinCenter(pos.x, pos.z, landingPlatform) && speed < 0.055) {
                    releaseMovement(mc);
                    if (++centeredTicks >= 2) {
                        landingSneak = false;
                        mc.options.keyShift.setDown(false);
                        landingPlatform = null;
                        transition(State.LAND);
                    } else mc.options.keyShift.setDown(false);
                } else {
                    centeredTicks = 0;
                    // Predict a few ticks ahead to begin braking before reaching center.
                    double desiredX = ex * 0.35 - vel.x * 1.8;
                    double desiredZ = ez * 0.35 - vel.z * 1.8;
                    if (Math.hypot(desiredX, desiredZ) < 0.012) releaseMovement(mc);
                    else steer(mc, desiredX, desiredZ, false, false);
                    landingSneak = false;
                    mc.options.keyShift.setDown(false);
                }
            }
            case RETURN_HOME, RETURN_WARP -> {
                release(mc);
                if (findTarget(mc) != null) {
                    returningHome = false;
                    emptyScanAttempts = 0;
                    transition(State.IDLE);
                    return;
                }
                if (returnMethod == ReturnMethod.TELEPORT) {
                    // Same client-side setPos mechanism as cheat mode. No commands.
                    // Home coordinates refer to player feet; +0.5 leaves room to settle.
                    mc.player.setPos(home.getX() + .5, home.getY() + .5, home.getZ() + .5);
                    mc.player.setDeltaMovement(Vec3.ZERO);
                    lastPlatePosition = mc.player.position();
                    transition(State.WAIT_FOR_START);
                    startWaitCycles = 0;
                    mc.player.displayClientMessage(Component.literal("SmartParkour: возврат к точке без команды"), true);
                } else {
                    lastPlatePosition = mc.player.position();
                    plateStuckTicks = 0;
                    transition(State.WALK_TO_PLATE);
                }
            }
            case WALK_TO_PLATE -> {
                if (!mc.player.onGround()) { release(mc); return; }
                if (homeReached(mc)) {
                    release(mc);
                    transition(State.WAIT_FOR_START);
                    startWaitCycles = 0;
                    mc.player.displayClientMessage(Component.literal("SmartParkour: плита достигнута, жду запуска"), true);
                    return;
                }
                if (phaseTicks > walkToPlateTimeoutTicks) {
                    release(mc);
                    mc.player.displayClientMessage(Component.literal("SmartParkour: не удалось дойти до плиты"), true);
                    reset(mc);
                    warpCooldownTicks = 200;
                    return;
                }
                Vec3 pos = mc.player.position();
                double dx = home.getX() + .5 - pos.x;
                double dz = home.getZ() + .5 - pos.z;
                double dist = Math.hypot(dx, dz);
                if (lastPlatePosition != null && pos.distanceToSqr(lastPlatePosition) < 0.0004) plateStuckTicks++;
                else plateStuckTicks = 0;
                lastPlatePosition = pos;
                if (plateStuckTicks > approachStallLimitTicks * 4) {
                    release(mc);
                    mc.player.displayClientMessage(Component.literal("SmartParkour: путь к плите заблокирован"), true);
                    reset(mc);
                    warpCooldownTicks = 200;
                    return;
                }
                // Don't walk into an unsupported gap. This is a short local approach,
                // not a general pathfinder; never blindly walk over an edge.
                if (!safeStepToPlate(mc, dx, dz)) {
                    release(mc);
                    mc.player.displayClientMessage(Component.literal("SmartParkour: перед плитой нет безопасного прохода"), true);
                    reset(mc);
                    warpCooldownTicks = 200;
                    return;
                }
                look(mc, home.getX() + .5, home.getZ() + .5);
                steer(mc, dx / Math.max(dist, .001), dz / Math.max(dist, .001), false, false);
                mc.options.keyShift.setDown(false);
            }
            case WAIT_FOR_START -> {
                release(mc);
                // Wait for the plate to activate the course. A gray-wool target
                // is the actual start signal, not merely completing the warp.
                if (findTarget(mc) != null) {
                    returningHome = false;
                    warpCommandSent = false;
                    warpOrigin = null;
                    missingTargetTicks = 0;
                    warpCooldownTicks = 200;
                    transition(State.IDLE);
                    startWaitCycles = 0;
                    inactivityRecoveryAttempts = 0;
                    mc.player.displayClientMessage(Component.literal("SmartParkour: паркур запущен"), true);
                } else if (phaseTicks >= startWaitTicks) {
                    // Waiting forever is not recovery. Retry the configured return method.
                    phaseTicks = 0;
                    if (inactivityRescue && ++startWaitCycles >= 2) {
                        startWaitCycles = 0;
                        transition(State.RETURN_HOME);
                        mc.player.displayClientMessage(Component.literal("SmartParkour: старт не подтверждён, повторяю возврат"), true);
                    }
                }
            }
            case CHEAT_SETTLE -> {
                release(mc);
                if (cheatExpectedLanding == null || cheatRequestedPosition == null) { recoverCheat(mc, "потеряна цель телепортации"); return; }
                Vec3 actual = mc.player.position();
                if (cheatTeleportTrigger == CheatTeleportTrigger.NEW_BLOCK) {
                    // A newly appeared block ACKs the *previous* teleport; it is
                    // appended to the queue, never used as an immediate destination.
                    boolean appeared = scanCheatQueue(mc);
                    if (appeared && cheatAwaitingSignal) {
                        cheatAwaitingSignal = false;
                        BlockPos next = pollCheatTarget(mc);
                        if (next != null) {
                            BlockPos old = cheatExpectedLanding;
                            Vec3 nextPos = new Vec3(next.getX() + .5, next.getY() + 1.5, next.getZ() + .5);
                            mc.player.setDeltaMovement(Vec3.ZERO);
                            mc.player.setPos(nextPos.x, nextPos.y, nextPos.z);
                            previousPlatform = old;
                            cheatExpectedLanding = next;
                            cheatRequestedPosition = nextPos;
                            cheatAwaitingSignal = true;
                            phaseTicks = 0;
                        }
                        return;
                    }
                    // An ACK with no queued target may still be waiting for world updates.
                    // A missing ACK is a stalled action: recover instead of disabling the mod.
                    if (!cheatAwaitingSignal) {
                        if (phaseTicks >= phaseTimeoutTicks) recoverCheat(mc, "очередь пуста после подтверждения");
                        return;
                    }
                    if (phaseTicks >= CHEAT_SETTLE_TIMEOUT_TICKS) {
                        recoverCheat(mc, "нет подтверждения нового блока");
                    }
                    return;
                }
                if (mc.player.onGround()) {
                    BlockPos standing = mc.player.blockPosition().below();
                    if (standing.equals(cheatExpectedLanding)) {
                        cheatExpectedLanding = null;
                        cheatRequestedPosition = null;
                        cheatVisibleBeforeTeleport.clear();
                        transition(State.IDLE);
                    } else {
                        // Server rejected the move or player landed elsewhere.
                        mc.player.displayClientMessage(Component.literal("SmartParkour: телепорт не подтверждён сервером"), true);
                        recoverCheat(mc, "сервер отклонил телепортацию");
                    }
                } else if (phaseTicks >= CHEAT_SETTLE_TIMEOUT_TICKS
                        || actual.distanceToSqr(cheatRequestedPosition) > 9.0) {
                    mc.player.displayClientMessage(Component.literal("SmartParkour: телепорт отклонён или нет приземления"), true);
                    recoverCheat(mc, "телепортация не завершилась");
                }
            }
            case LAND -> {
                release(mc);
                if (phaseTicks >= (movementMode == MovementMode.BALANCED ? 0 : jumpDelayTicks)) transition(State.IDLE);
            }
        }
    }
    // Cheat mode uses the same recovery destination and method as the other modes.
    // Keep the mod enabled and let the normal return state handle retries.
    private void recoverCheat(Minecraft mc, String reason) {
        release(mc);
        reset(mc);
        returningHome = true;
        transition(State.RETURN_HOME);
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("SmartParkour: " + reason + ", восстановление"), true);
    }

    private double maxApproachDisplacement(Vec3 pos, BlockPos block) {
        return Math.max(Math.abs(pos.x - block.getX() - 0.5), Math.abs(pos.z - block.getZ() - 0.5));
    }
    private boolean withinCenter(double x, double z, BlockPos block) {
        double tx = exactCenter ? 0.025 : centerToleranceXPixels / 16.0;
        double tz = exactCenter ? 0.025 : centerToleranceZPixels / 16.0;
        return Math.abs(x - block.getX() - 0.5) <= tx && Math.abs(z - block.getZ() - 0.5) <= tz;
    }
    private boolean safeStepToPlate(Minecraft mc, double dx, double dz) {
        Vec3 pos = mc.player.position();
        double d = Math.hypot(dx, dz);
        if (d < .001) return true;
        double probeX = pos.x + dx / d * .42;
        double probeZ = pos.z + dz / d * .42;
        BlockPos below = BlockPos.containing(probeX, pos.y - .2, probeZ);
        return mc.level.getBlockState(below).isCollisionShapeFullBlock(mc.level, below);
    }
    private boolean homeReached(Minecraft mc) {
        Vec3 pos = mc.player.position();
        return mc.player.onGround() && Math.abs(pos.x - (home.getX() + .5)) < .30
            && Math.abs(pos.z - (home.getZ() + .5)) < .30
            && Math.abs(pos.y - home.getY()) < .12;
    }
    private void homeComplete(Minecraft mc) {
        release(mc);
        returningHome = false;
        warpCommandSent = false;
        warpOrigin = null;
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
    private void clearRoute() { routeStep = 0; routeOriginal = null; routeIntermediate = null; }

    // Only reroute while still standing on the departure platform.
    // A new wool platform on the line to the locked target takes precedence.
    private boolean rerouteThroughIntermediate(Minecraft mc) {
        if (routeStep != 0 || target == null || departure == null) return false;
        double ax = departure.getX() + .5, az = departure.getZ() + .5;
        double bx = target.getX() + .5, bz = target.getZ() + .5;
        double vx = bx - ax, vz = bz - az;
        double lengthSq = vx * vx + vz * vz;
        if (lengthSq < 2.0) return false;
        BlockPos middle = null;
        double bestT = Double.POSITIVE_INFINITY;
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++)
            for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++)
                for (int y = -1; y <= 1; y++) {
                    BlockPos p = departure.offset(x, y, z);
                    if (p.equals(departure) || p.equals(target) || !isTarget(mc, p)) continue;
                    double wx = p.getX() + .5 - ax, wz = p.getZ() + .5 - az;
                    double t = (wx * vx + wz * vz) / lengthSq;
                    if (t <= .02 || t >= .98) continue;
                    double off = Math.hypot(wx - t * vx, wz - t * vz);
                    if (off > .65 || Math.hypot(wx, wz) < 0.95) continue;
                    if (t < bestT) { bestT = t; middle = p.immutable(); }
                }
        if (middle == null) return false;
        routeOriginal = target.immutable();
        routeIntermediate = middle;
        routeStep = 1;
        target = middle;
        release(mc);
        transition(State.ROTATE);
        return true;
    }

    private void snapshotCheatTargets(Minecraft mc, BlockPos center) {
        cheatKnownPlatforms.clear();
        cheatQueue.clear();
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++)
            for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++)
                for (int y = -1; y <= 1; y++) {
                    BlockPos p = center.offset(x, y, z);
                    if (isTarget(mc, p)) cheatKnownPlatforms.add(p.immutable());
                }
    }
    private boolean scanCheatQueue(Minecraft mc) {
        // Keep the scanned region around the expected platform, even while airborne.
        BlockPos center = cheatExpectedLanding;
        if (center == null) return false;
        boolean appeared = false;
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++)
            for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++)
                for (int y = -1; y <= 1; y++) {
                    BlockPos p = center.offset(x, y, z);
                    if (!isTarget(mc, p)) continue;
                    BlockPos immutable = p.immutable();
                    if (cheatKnownPlatforms.add(immutable)) {
                        appeared = true;
                        if (!immutable.equals(center) && !immutable.equals(previousPlatform))
                            cheatQueue.addLast(immutable);
                    }
                }
        return appeared;
    }
    private BlockPos pollCheatTarget(Minecraft mc) {
        while (!cheatQueue.isEmpty()) {
            BlockPos p = cheatQueue.removeFirst();
            if (!p.equals(cheatExpectedLanding) && isTarget(mc, p)) return p;
        }
        return null;
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
                if (horizontal < 0.95 || horizontal > 5.2) continue;
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
        double right = -Math.cos(yaw) * dx - Math.sin(yaw) * dz;
        // Normalize to avoid a movement dead zone for short correction vectors.
        double magnitude = Math.hypot(forward, right);
        if (magnitude > 1.0e-5) { forward /= magnitude; right /= magnitude; }
        mc.options.keyUp.setDown(forward > .15);
        mc.options.keyDown.setDown(forward < -.15);
        mc.options.keyRight.setDown(right > .15);
        mc.options.keyLeft.setDown(right < -.15);
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
    private void clearInactivityWatch() {
        inactivityTicks = 0;
        lastWatchPlatform = null;
        lastWatchTarget = null;
        lastWatchQueueSize = -1;
    }
    private boolean checkInactivity(Minecraft mc) {
        // Do not mistake animation, rotation or sub-block jitter for route progress.
        BlockPos platform = mc.player.onGround() ? mc.player.blockPosition().below() : null;
        boolean platformChanged = platform != null && lastWatchPlatform != null && !platform.equals(lastWatchPlatform);
        boolean queueAdvanced = movementMode == MovementMode.CHEAT && lastWatchQueueSize >= 0
            && cheatQueue.size() < lastWatchQueueSize;
        boolean destinationChanged = target != null && lastWatchTarget != null && !target.equals(lastWatchTarget)
            && (state == State.FLIGHT || state == State.CHEAT_SETTLE || state == State.LAND);
        if (platformChanged || queueAdvanced || destinationChanged) {
            inactivityTicks = 0;
            inactivityRecoveryAttempts = 0;
        } else if (state != State.IDLE || target != null || returningHome) {
            inactivityTicks++;
        } else {
            // IDLE with no target is handled by the independent three-scan recovery.
            inactivityTicks = 0;
        }
        lastWatchPlatform = platform;
        lastWatchTarget = target;
        lastWatchQueueSize = cheatQueue.size();
        if (inactivityTicks >= inactivityTimeoutTicks) {
            inactivityTicks = 0;
            return true;
        }
        return false;
    }
    private void reset(Minecraft mc) { emptyScanAttempts = 0; emptyScanInterval = 0; warpCommandSent = false; warpOrigin = null; warpCooldownTicks = 0; lastPlatePosition = null; plateStuckTicks = 0; warpDetected = false; clearInactivityWatch(); release(mc); returningHome = false; missingTargetTicks = 0; state = State.IDLE; target = null; departure = null; landingPlatform = null; centeredTicks = 0; queuedNextTarget = null; cheatExpectedLanding = null; cheatRequestedPosition = null; cheatVisibleBeforeTeleport.clear(); cheatKnownPlatforms.clear(); cheatQueue.clear(); cheatAwaitingSignal = false; facingTicks = 0; airborneSeen = false; phaseTicks = 0; clearRoute(); }
    private void transition(State next) { if (next != State.APPROACH || state != State.APPROACH) { approachStallTicks = 0; lastApproachPosition = null; } state = next; phaseTicks = 0; if (next == State.ROTATE) facingTicks = 0; if (next == State.FLIGHT) { airborneSeen = false; landingSneak = false; } }
    private static int readTicks(Properties p, String key, int def, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(p.getProperty(key, Integer.toString(def))))); }
        catch (NumberFormatException ex) { return def; }
    }
    private void loadConfig() {
        if (!Files.isRegularFile(configPath)) return;
        try {
            Properties props = new Properties();
            props.load(new StringReader(Files.readString(configPath, StandardCharsets.UTF_8)));
            int x = Integer.parseInt(props.getProperty("homeX", "-65"));
            int y = Integer.parseInt(props.getProperty("homeY", "84"));
            int z = Integer.parseInt(props.getProperty("homeZ", "306"));
            home = new BlockPos(x, y, z);
            try { movementMode = MovementMode.valueOf(props.getProperty("movementMode", "CAUTIOUS")); }
            catch (IllegalArgumentException ex) { movementMode = MovementMode.CAUTIOUS; }
            try { returnMethod = ReturnMethod.valueOf(props.getProperty("returnMethod", "TELEPORT")); }
            catch (IllegalArgumentException ex) { returnMethod = ReturnMethod.TELEPORT; }
            try { cheatTeleportTrigger = CheatTeleportTrigger.valueOf(props.getProperty("cheatTeleportTrigger", "ON_LANDING")); }
            catch (IllegalArgumentException ex) { cheatTeleportTrigger = CheatTeleportTrigger.ON_LANDING; }
            jumpDelayTicks = readTicks(props, "delayTicks", 2, 0, 200);
            centerTimeoutTicks = readTicks(props, "centerTimeoutTicks", 12, 1, 200);
            inactivityTimeoutTicks = readTicks(props, "inactivityTimeoutTicks", 100, 20, 600);
            noTargetTicks = readTicks(props, "noTargetTicks", 12, 1, 200);
            phaseTimeoutTicks = readTicks(props, "phaseTimeoutTicks", 120, 20, 600);
            approachStallLimitTicks = readTicks(props, "approachStallTicks", 5, 1, 100);
            facingConfirmTicks = readTicks(props, "facingConfirmTicks", 1, 1, 20);
            homeFacingConfirmTicks = readTicks(props, "homeFacingConfirmTicks", 2, 1, 20);
            warpWaitTicks = readTicks(props, "warpWaitTicks", 160, 20, 600);
            walkToPlateTimeoutTicks = readTicks(props, "walkToPlateTimeoutTicks", 240, 20, 1200);
            startWaitTicks = readTicks(props, "startWaitTicks", 100, 20, 600);
            centerToleranceXPixels = readTicks(props, "centerToleranceXPixels", 3, 0, 8);
            centerToleranceZPixels = readTicks(props, "centerToleranceZPixels", 3, 0, 8);
            exactCenter = Boolean.parseBoolean(props.getProperty("exactCenter", "false"));
            inactivityRescue = Boolean.parseBoolean(props.getProperty("inactivityRescue", "false"));
            continueInMenus = Boolean.parseBoolean(props.getProperty("continueInMenus", "false"));
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
            props.setProperty("movementMode", movementMode.name());
            props.setProperty("returnMethod", returnMethod.name());
            props.setProperty("cheatTeleportTrigger", cheatTeleportTrigger.name());
            props.setProperty("delayTicks", Integer.toString(jumpDelayTicks));
            props.setProperty("centerTimeoutTicks", Integer.toString(centerTimeoutTicks));
            props.setProperty("inactivityTimeoutTicks", Integer.toString(inactivityTimeoutTicks));
            props.setProperty("noTargetTicks", Integer.toString(noTargetTicks));
            props.setProperty("phaseTimeoutTicks", Integer.toString(phaseTimeoutTicks));
            props.setProperty("approachStallTicks", Integer.toString(approachStallLimitTicks));
            props.setProperty("facingConfirmTicks", Integer.toString(facingConfirmTicks));
            props.setProperty("homeFacingConfirmTicks", Integer.toString(homeFacingConfirmTicks));
            props.setProperty("warpWaitTicks", Integer.toString(warpWaitTicks));
            props.setProperty("walkToPlateTimeoutTicks", Integer.toString(walkToPlateTimeoutTicks));
            props.setProperty("startWaitTicks", Integer.toString(startWaitTicks));
            props.setProperty("centerToleranceXPixels", Integer.toString(centerToleranceXPixels));
            props.setProperty("centerToleranceZPixels", Integer.toString(centerToleranceZPixels));
            props.setProperty("exactCenter", Boolean.toString(exactCenter));
            props.setProperty("inactivityRescue", Boolean.toString(inactivityRescue));
            props.setProperty("continueInMenus", Boolean.toString(continueInMenus));
            StringWriter writer = new StringWriter();
            props.store(writer, "SmartParkour settings");
            Files.writeString(configPath, writer.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            System.err.println("SmartParkour: cannot save config: " + ex.getMessage());
        }
    }
    private String modeLabel() {
        return switch (movementMode) {
            case CAUTIOUS -> "Осторожный";
            case BALANCED -> "Сбалансированный";
            case CHEAT -> "Читерский";
        };
    }
    private final class SettingsScreen extends Screen {
        private enum Page { HOME, MOVEMENT, TELEPORT, RECOVERY, GENERAL, TIMERS, RECOVERY_TIMERS, CENTERING }
        private Page page = Page.HOME;
        private EditBox xField, yField, zField;
        private Component error = Component.empty();
        SettingsScreen() { super(Component.literal("SmartParkour 1.0.0 — настройки")); }
        private void navigate(Page next) { page = next; clearWidgets(); init(); }
        private void navButton(int cx, int y, String label, Page next) {
            addRenderableWidget(Button.builder(Component.literal(label), b -> navigate(next))
                .bounds(cx - 110, y, 220, 20).build());
        }
        @Override protected void init() {
            switch (page) {
                case HOME -> initHome();
                case MOVEMENT -> initMovement();
                case TELEPORT -> initTeleport();
                case RECOVERY -> initRecovery();
                case GENERAL -> initGeneral();
                case TIMERS -> initTimers();
                case RECOVERY_TIMERS -> initRecoveryTimers();
                case CENTERING -> initCentering();
            }
        }
        private void initHome() {
            int cx = width / 2, y = height / 2 - 88;
            navButton(cx, y, "Движение и прыжки", Page.MOVEMENT);
            navButton(cx, y + 28, "Читерская телепортация", Page.TELEPORT);
            navButton(cx, y + 56, "Возврат и восстановление", Page.RECOVERY);
            navButton(cx, y + 84, "Дополнительные настройки", Page.GENERAL);
            addRenderableWidget(Button.builder(Component.literal("Сохранить и закрыть"), b -> onClose())
                .bounds(cx - 110, y + 130, 220, 20).build());
        }
        private void initMovement() {
            int cx = width / 2, y = height / 2 - 85;
            addRenderableWidget(Button.builder(Component.literal("Режим: " + modeLabel()), b -> {
                movementMode = switch (movementMode) {
                    case CAUTIOUS -> MovementMode.BALANCED;
                    case BALANCED -> MovementMode.CHEAT;
                    case CHEAT -> MovementMode.CAUTIOUS;
                };
                reset(Minecraft.getInstance()); saveConfig();
                b.setMessage(Component.literal("Режим: " + modeLabel()));
            }).bounds(cx - 110, y, 220, 20).build());
            addRenderableWidget(new AbstractSliderButton(cx - 110, y + 30, 220, 20,
                Component.empty(), jumpDelayTicks / 100.0) {
                { updateMessage(); }
                @Override protected void updateMessage() { setMessage(Component.literal("Задержка прыжка: " + jumpDelayTicks * 50 + " мс")); }
                @Override protected void applyValue() {
                    jumpDelayTicks = Math.max(0, Math.min(100, (int)Math.round(value * 100)));
                    updateMessage();
                }
            });
            navButton(cx, y + 60, "Точность центрирования...", Page.CENTERING);
            navButton(cx, y + 90, "Все таймеры...", Page.TIMERS);
            navButton(cx, y + 130, "Назад", Page.HOME);
        }
        private void initTeleport() {
            int cx = width / 2, y = height / 2 - 48;
            addRenderableWidget(Button.builder(Component.literal("Следующий ТП: " +
                (cheatTeleportTrigger == CheatTeleportTrigger.NEW_BLOCK ? "Новый блок" : "Приземление")), b -> {
                cheatTeleportTrigger = cheatTeleportTrigger == CheatTeleportTrigger.NEW_BLOCK
                    ? CheatTeleportTrigger.ON_LANDING : CheatTeleportTrigger.NEW_BLOCK;
                reset(Minecraft.getInstance()); saveConfig();
                b.setMessage(Component.literal("Следующий ТП: " +
                    (cheatTeleportTrigger == CheatTeleportTrigger.NEW_BLOCK ? "Новый блок" : "Приземление")));
            }).bounds(cx - 110, y, 220, 20).build());
            navButton(cx, y + 40, "Назад", Page.HOME);
        }
        private void initRecovery() {
            int cx = width / 2, y = height / 2 - 105;
            addRenderableWidget(Button.builder(Component.literal("Возврат: " +
                (returnMethod == ReturnMethod.TELEPORT ? "Телепорт" : "Пешком")), b -> {
                returnMethod = returnMethod == ReturnMethod.TELEPORT ? ReturnMethod.WALK : ReturnMethod.TELEPORT;
                reset(Minecraft.getInstance()); saveConfig();
                b.setMessage(Component.literal("Возврат: " +
                    (returnMethod == ReturnMethod.TELEPORT ? "Телепорт" : "Пешком")));
            }).bounds(cx - 110, y, 220, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Спасение от бездействия: " +
                (inactivityRescue ? "ВКЛ" : "ВЫКЛ")), b -> {
                inactivityRescue = !inactivityRescue; clearInactivityWatch(); saveConfig();
                b.setMessage(Component.literal("Спасение от бездействия: " + (inactivityRescue ? "ВКЛ" : "ВЫКЛ")));
            }).bounds(cx - 110, y + 30, 220, 20).build());
            xField = new EditBox(font, cx - 110, y + 65, 68, 20, Component.literal("X"));
            yField = new EditBox(font, cx - 34, y + 65, 68, 20, Component.literal("Y"));
            zField = new EditBox(font, cx + 42, y + 65, 68, 20, Component.literal("Z"));
            xField.setValue(Integer.toString(home.getX()));
            yField.setValue(Integer.toString(home.getY()));
            zField.setValue(Integer.toString(home.getZ()));
            addRenderableWidget(xField); addRenderableWidget(yField); addRenderableWidget(zField);
            addRenderableWidget(Button.builder(Component.literal("Сохранить координаты"), b -> saveHome())
                .bounds(cx - 110, y + 95, 220, 20).build());
            navButton(cx, y + 125, "Таймеры возвращения...", Page.RECOVERY_TIMERS);
            navButton(cx, y + 155, "Назад", Page.HOME);
        }
        private void initGeneral() {
            int cx = width / 2, y = height / 2 - 40;
            addRenderableWidget(Button.builder(Component.literal("Паркур в меню: " +
                (continueInMenus ? "ВКЛ" : "ВЫКЛ")), b -> {
                continueInMenus = !continueInMenus; saveConfig();
                b.setMessage(Component.literal("Паркур в меню: " + (continueInMenus ? "ВКЛ" : "ВЫКЛ")));
            }).bounds(cx - 110, y, 220, 20).build());
            navButton(cx, y + 40, "Назад", Page.HOME);
        }
        private void initCentering() {
            int cx = width / 2;
            int y = height / 2 - 72;
            addRenderableWidget(Button.builder(Component.literal("Точный центр: " + (exactCenter ? "ВКЛ" : "ВЫКЛ")), b -> {
                exactCenter = !exactCenter;
                b.setMessage(Component.literal("Точный центр: " + (exactCenter ? "ВКЛ" : "ВЫКЛ")));
            }).bounds(cx - 110, y, 220, 20).build());
            addPixelsSlider(cx, y + 30, "Допуск X", () -> centerToleranceXPixels, v -> centerToleranceXPixels = v);
            addPixelsSlider(cx, y + 60, "Допуск Z", () -> centerToleranceZPixels, v -> centerToleranceZPixels = v);
            addRenderableWidget(Button.builder(Component.literal("Назад / сохранить"), b -> {
                saveConfig(); navigate(Page.MOVEMENT);
            }).bounds(cx - 110, y + 100, 220, 20).build());
        }
        private void addPixelsSlider(int cx, int y, String title,
                                     java.util.function.IntSupplier getter, java.util.function.IntConsumer setter) {
            addRenderableWidget(new AbstractSliderButton(cx - 110, y, 220, 20,
                    Component.empty(), getter.getAsInt() / 8.0) {
                { updateMessage(); }
                @Override protected void updateMessage() {
                    setMessage(Component.literal(title + ": ±" + getter.getAsInt() + " пикс."));
                }
                @Override protected void applyValue() {
                    setter.accept((int)Math.round(value * 8)); updateMessage();
                }
            });
        }
        private void initTimers() {
            int cx = width / 2;
            int y = height / 2 - 124;
            addTimer(cx, y, "Задержка прыжка", 0, 200, () -> jumpDelayTicks, v -> jumpDelayTicks = v);
            addTimer(cx, y + 27, "Центрирование", 1, 200, () -> centerTimeoutTicks, v -> centerTimeoutTicks = v);
            addTimer(cx, y + 54, "Бездействие", 20, 600, () -> inactivityTimeoutTicks, v -> inactivityTimeoutTicks = v);
            addTimer(cx, y + 81, "Нет платформ", 1, 200, () -> noTargetTicks, v -> noTargetTicks = v);
            addTimer(cx, y + 108, "Тайм-аут этапа", 20, 600, () -> phaseTimeoutTicks, v -> phaseTimeoutTicks = v);
            addTimer(cx, y + 135, "Застревание подхода", 1, 100, () -> approachStallLimitTicks, v -> approachStallLimitTicks = v);
            addTimer(cx, y + 162, "Поворот", 1, 20, () -> facingConfirmTicks, v -> facingConfirmTicks = v);
            addTimer(cx, y + 189, "Поворот домой", 1, 20, () -> homeFacingConfirmTicks, v -> homeFacingConfirmTicks = v);
            addRenderableWidget(Button.builder(Component.literal("Назад / сохранить"), b -> {
                saveConfig(); navigate(Page.MOVEMENT);
            }).bounds(cx - 110, y + 244, 220, 20).build());
        }
        private void initRecoveryTimers() {
            int cx = width / 2;
            int y = height / 2 - 65;
            addTimer(cx, y, "Ожидание варпа", 20, 600, () -> warpWaitTicks, v -> warpWaitTicks = v);
            addTimer(cx, y + 30, "Подход к плите", 20, 1200, () -> walkToPlateTimeoutTicks, v -> walkToPlateTimeoutTicks = v);
            addTimer(cx, y + 60, "Ожидание запуска", 20, 600, () -> startWaitTicks, v -> startWaitTicks = v);
            addRenderableWidget(Button.builder(Component.literal("Назад / сохранить"), b -> {
                saveConfig(); navigate(Page.RECOVERY);
            }).bounds(cx - 110, y + 100, 220, 20).build());
        }
        private void addTimer(int cx, int y, String title, int min, int max,
                              java.util.function.IntSupplier getter, java.util.function.IntConsumer setter) {
            addRenderableWidget(new AbstractSliderButton(cx - 110, y, 220, 20,
                    Component.empty(), (getter.getAsInt() - min) / (double)(max - min)) {
                { updateMessage(); }
                @Override protected void updateMessage() {
                    setMessage(Component.literal(title + ": " + (getter.getAsInt() * 50) + " мс"));
                }
                @Override protected void applyValue() {
                    setter.accept(min + (int)Math.round(value * (max - min)));
                    updateMessage();
                }
            });
        }
        private void saveHome() {
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
                error = Component.empty();
                navigate(Page.RECOVERY);
            } catch (NumberFormatException ex) {
                error = Component.literal("Введите целые координаты X, Y, Z");
            }
        }
        @Override public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            super.render(graphics, mouseX, mouseY, delta);
            graphics.drawCenteredString(font, "SmartParkour 1.0.0", width / 2, 14, 0xFFFFFF);
            if (!error.getString().isEmpty())
                graphics.drawCenteredString(font, error, width / 2, height - 22, 0xFF5555);
        }
        @Override public void onClose() {
            saveConfig(); Minecraft.getInstance().setScreen(null);
        }
        @Override public boolean isPauseScreen() { return false; }
    }
}
