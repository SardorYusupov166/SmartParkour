package dev.smartparkour;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/** Experimental client-only controller for one-block, gray-wool dynamic parkour. */
public final class SmartParkour implements ClientModInitializer {
    private enum Phase { WAIT, APPROACH, AIRBORNE }
    private boolean enabled, previousToggle;
    private Phase phase = Phase.WAIT;
    private BlockPos target, takeoff;
    private int groundedTicks, waitingTicks, airborneTicks;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient c) {
        if (c.player == null || c.world == null) { enabled = false; return; }
        boolean pressed = GLFW.glfwGetKey(c.getWindow().getHandle(), GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
        if (pressed && !previousToggle) {
            enabled = !enabled;
            reset(c);
            c.player.sendMessage(net.minecraft.text.Text.literal("SmartParkour: " + (enabled ? "ON" : "OFF")), true);
        }
        previousToggle = pressed;
        if (!enabled) return;
        boolean grounded = c.player.isOnGround();
        groundedTicks = grounded ? groundedTicks + 1 : 0;
        if (groundedTicks > 2 && phase == Phase.AIRBORNE) {
            // Only accept a landing on the intended gray-wool block.
            BlockPos below = c.player.getBlockPos().down();
            if (below.equals(target)) {
                takeoff = target;
                target = null;
                phase = Phase.WAIT;
                waitingTicks = 0;
            } else { reset(c); return; }
        }
        if (phase == Phase.WAIT) {
            release(c);
            if (!grounded || groundedTicks < 3) return;
            if (++waitingTicks > 200) waitingTicks = 200;
            BlockPos next = findTarget(c);
            if (next == null) return;
            target = next;
            takeoff = c.player.getBlockPos().down();
            phase = Phase.APPROACH;
        }
        if (target == null || !validTarget(c, target)) { reset(c); return; }
        if (phase == Phase.APPROACH) {
            if (!grounded) { phase = Phase.AIRBORNE; airborneTicks = 0; return; }
            Vec3d position = c.player.getPos();
            Vec3d destination = Vec3d.ofCenter(target).add(0, 0.5, 0);
            double dx = destination.x - position.x, dz = destination.z - position.z;
            double distance = Math.hypot(dx, dz);
            double vx = c.player.getVelocity().x, vz = c.player.getVelocity().z;
            double speedToward = distance > 0.01 ? (vx * dx + vz * dz) / distance : 0;
            // Rotate to the target and build forward velocity. Jump before leaving the edge.
            aim(c, dx, dz);
            c.options.forwardKey.setPressed(true);
            c.options.sprintKey.setPressed(true);
            c.options.jumpKey.setPressed(false);
            c.options.leftKey.setPressed(false);
            c.options.rightKey.setPressed(false);
            c.options.backKey.setPressed(false);
            double edge = distanceToEdge(c, takeoff);
            if (edge < 0.24 || (distance < 2.4 && speedToward > 0.12)) {
                c.options.jumpKey.setPressed(true);
                phase = Phase.AIRBORNE;
                airborneTicks = 0;
            }
        } else if (phase == Phase.AIRBORNE) {
            airborneTicks++;
            if (airborneTicks > 35) { reset(c); return; }
            Vec3d p = c.player.getPos();
            Vec3d v = c.player.getVelocity();
            double dx = target.getX() + .5 - p.x, dz = target.getZ() + .5 - p.z;
            aim(c, dx, dz);
            c.options.jumpKey.setPressed(false);
            c.options.sprintKey.setPressed(true);
            // Brake near the landing center to reduce overshoot; steering is intentionally conservative.
            double along = Math.hypot(dx, dz);
            double projected = along > .01 ? (v.x * dx + v.z * dz) / along : 0;
            c.options.forwardKey.setPressed(along > .32 && projected < .22);
            c.options.backKey.setPressed(along < .25 && projected < -.08);
            c.options.leftKey.setPressed(false);
            c.options.rightKey.setPressed(false);
        }
    }

    private BlockPos findTarget(MinecraftClient c) {
        BlockPos origin = c.player.getBlockPos().down();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = -1; y <= 1; y++) {
            BlockPos b = origin.add(x, y, z);
            if (b.equals(origin) || b.equals(takeoff) || !validTarget(c, b)) continue;
            double horizontal = Math.hypot(b.getX() - origin.getX(), b.getZ() - origin.getZ());
            // Deliberately conservative reachability limits, not a guarantee of landing.
            if (horizontal < 1.1 || horizontal > (y > 0 ? 3.0 : 4.0)) continue;
            if (y > 1 || y < -1) continue;
            double score = horizontal + Math.abs(y) * 1.2;
            if (score < bestScore) { bestScore = score; best = b.toImmutable(); }
        }
        return best;
    }

    private boolean validTarget(MinecraftClient c, BlockPos b) {
        if (!c.world.isChunkLoaded(b)) return false;
        BlockState floor = c.world.getBlockState(b);
        return floor.isOf(Blocks.GRAY_WOOL)
            && c.world.getBlockState(b.up()).getCollisionShape(c.world, b.up()).isEmpty()
            && c.world.getBlockState(b.up(2)).getCollisionShape(c.world, b.up(2)).isEmpty();
    }

    private static void aim(MinecraftClient c, double dx, double dz) {
        float yaw = (float)(Math.toDegrees(Math.atan2(-dx, dz)));
        float current = c.player.getYaw();
        c.player.setYaw(current + MathHelper.clamp(MathHelper.wrapDegrees(yaw - current), -22f, 22f));
    }

    private static double distanceToEdge(MinecraftClient c, BlockPos b) {
        if (b == null) return 0;
        Vec3d p = c.player.getPos();
        double x = Math.min(p.x - b.getX(), b.getX() + 1 - p.x);
        double z = Math.min(p.z - b.getZ(), b.getZ() + 1 - p.z);
        return Math.min(x, z);
    }

    private void reset(MinecraftClient c) {
        target = null;
        takeoff = null;
        groundedTicks = 0;
        waitingTicks = 0;
        airborneTicks = 0;
        phase = Phase.WAIT;
        release(c);
    }
    private static void release(MinecraftClient c) {
        c.options.forwardKey.setPressed(false);
        c.options.backKey.setPressed(false);
        c.options.leftKey.setPressed(false);
        c.options.rightKey.setPressed(false);
        c.options.jumpKey.setPressed(false);
        c.options.sprintKey.setPressed(false);
    }
}
