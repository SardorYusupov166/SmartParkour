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
    private enum Phase { WAIT, BRAKE, APPROACH, AIRBORNE, FALLBACK, FALLBACK_DONE }
    // Every observed gray-wool appearance has its own identity. A vanished block
    // can reappear at the same coordinates and is then a fresh candidate.
    private final java.util.Map<BlockPos, Long> livePlatforms = new java.util.HashMap<>();
    private final java.util.Set<Long> visited = new java.util.HashSet<>();
    private long nextPlatformId = 1;
    private boolean sawAirborne = false;
    // Two indistinguishable platforms appearing simultaneously have no knowable order.

    private boolean enabled, previousToggle;
    private Phase phase = Phase.WAIT;
    private BlockPos target, takeoff;
    private int groundedTicks, waitingTicks, airborneTicks, brakeTicks;
    private double homeX = 0, homeY = 64, homeZ = 0;
    private boolean homeConfigured = false, fallbackUsed = false;

    @Override public void onInitializeClient() {
        loadConfig();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient c) {
        if (c.player == null || c.world == null) { enabled = false; return; }
        boolean pressed = GLFW.glfwGetKey(c.getWindow().getHandle(), GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
        if (pressed && !previousToggle) {
            enabled = !enabled;
            reset(c);
            fallbackUsed = false;
            visited.clear();
            livePlatforms.clear();
            nextPlatformId = 1;
            if (enabled) {
                updatePlatforms(c);
                markVisited(c.player.getBlockPos().down());
            }
            c.player.sendMessage(net.minecraft.text.Text.literal("SmartParkour: " + (enabled ? "ON" : "OFF")), true);
        }
        previousToggle = pressed;
        if (!enabled) return;
        updatePlatforms(c);
        boolean grounded = c.player.isOnGround();
        groundedTicks = grounded ? groundedTicks + 1 : 0;
        if (!grounded) sawAirborne = true;
        if (groundedTicks > 2 && sawAirborne && phase == Phase.AIRBORNE) {
            // Only accept a landing on the intended gray-wool block.
            BlockPos below = c.player.getBlockPos().down();
            if (below.equals(target)) {
                takeoff = target;
                markVisited(target);
                target = null;
                phase = Phase.BRAKE;
                brakeTicks = 0;
                waitingTicks = 0;
                fallbackUsed = false;
            } else { reset(c); return; }
        }
        if (phase == Phase.BRAKE) {
            release(c);
            if (!grounded) return;
            // Do not alter velocity directly: use ordinary opposite-direction input.
            Vec3d v = c.player.getVelocity();
            double speed = Math.hypot(v.x, v.z);
            if (speed > .025) {
                double yaw = Math.toRadians(c.player.getYaw());
                double forward = -Math.sin(yaw) * v.x + Math.cos(yaw) * v.z;
                double side = Math.cos(yaw) * v.x + Math.sin(yaw) * v.z;
                c.options.backKey.setPressed(forward > .025);
                c.options.forwardKey.setPressed(forward < -.025);
                c.options.leftKey.setPressed(side > .025);
                c.options.rightKey.setPressed(side < -.025);
                brakeTicks = 0;
                return;
            }
            if (++brakeTicks < 4) return;
            release(c);
            phase = Phase.WAIT;
            waitingTicks = 0;
        }
        if (phase == Phase.FALLBACK_DONE) { release(c); phase = Phase.WAIT; return; }
        if (phase == Phase.FALLBACK) {
            release(c);
            if (!grounded) return;
            Vec3d pos = c.player.getEntityPos();
            double dx = homeX - pos.x, dz = homeZ - pos.z;
            if (Math.hypot(dx, dz) < .65 && Math.abs(homeY - pos.y) < 1.6) {
                // Re-arm parkour at the fallback destination, not the same fallback trip.
                visited.clear();
                markVisited(c.player.getBlockPos().down());
                fallbackUsed = true;
                phase = Phase.FALLBACK_DONE;
                return;
            }
            // Safe ground-only movement: no crossing gaps or obstacle pathfinding.
            double len = Math.hypot(dx, dz);
            if (len < .01) return;
            aim(c, dx, dz);
            float yawDiff = MathHelper.wrapDegrees((float)Math.toDegrees(Math.atan2(-dx, dz)) - c.player.getYaw());
            if (Math.abs(yawDiff) > 7) return;
            double stepX = dx / len * .65, stepZ = dz / len * .65;
            BlockPos feet = c.player.getBlockPos();
            BlockPos ahead = BlockPos.ofFloored(pos.x + stepX, pos.y, pos.z + stepZ);
            if (!c.world.getBlockState(ahead.down()).isSolidBlock(c.world, ahead.down()) ||
                !c.world.getBlockState(ahead).getCollisionShape(c.world, ahead).isEmpty() ||
                !c.world.getBlockState(ahead.up()).getCollisionShape(c.world, ahead.up()).isEmpty()) return;
            if (Math.abs(c.player.getVelocity().x) + Math.abs(c.player.getVelocity().z) > .18) return;
            c.options.forwardKey.setPressed(true);
            return;
        }
        if (phase == Phase.WAIT) {
            release(c);
            if (!grounded || groundedTicks < 3) return;
            if (++waitingTicks > 200) waitingTicks = 200;
            BlockPos next = findTarget(c);
            if (next == null) {
                // Never leave for fallback while another gray-wool platform exists.
                if (homeConfigured && !fallbackUsed && waitingTicks >= 10 && !hasOtherWool(c)) phase = Phase.FALLBACK;
                return;
            }
            target = next;
            takeoff = c.player.getBlockPos().down();
            phase = Phase.APPROACH;
        }
        if (target == null || !validTarget(c, target)) { reset(c); return; }
        if (phase == Phase.APPROACH) {
            if (!grounded) { phase = Phase.AIRBORNE; airborneTicks = 0; return; }
            Vec3d position = c.player.getEntityPos();
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
                sawAirborne = false;
                airborneTicks = 0;
            }
        } else if (phase == Phase.AIRBORNE) {
            airborneTicks++;
            if (airborneTicks > 35) { reset(c); return; }
            Vec3d p = c.player.getEntityPos();
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
            Long id = livePlatforms.get(b);
            if (b.equals(origin) || b.equals(takeoff) || id == null || visited.contains(id) || !validTarget(c, b)) continue;
            double horizontal = Math.hypot(b.getX() - origin.getX(), b.getZ() - origin.getZ());
            // Deliberately conservative reachability limits, not a guarantee of landing.
            if (horizontal < 1.1 || horizontal > (y > 0 ? 3.0 : 4.0)) continue;
            if (y > 1 || y < -1) continue;
            double score = horizontal + Math.abs(y) * 1.2;
            if (score < bestScore) { bestScore = score; best = b.toImmutable(); }
        }
        return best;
    }


    /** Scan around the player, retaining identities for blocks still present.
     *  Only positions within the scanned loaded region can be removed; a block
     *  that leaves scan range keeps its identity until revisited and observed gone.
     */
    private void updatePlatforms(MinecraftClient c) {
        BlockPos center = c.player.getBlockPos();
        for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) for (int y = -3; y <= 3; y++) {
            BlockPos pos = center.add(x, y, z);
            if (!c.world.isChunkLoaded(pos)) continue;
            if (c.world.getBlockState(pos).isOf(Blocks.GRAY_WOOL)) {
                if (!livePlatforms.containsKey(pos)) livePlatforms.put(pos.toImmutable(), nextPlatformId++);
            } else {
                livePlatforms.remove(pos);
            }
        }
    }

    private void markVisited(BlockPos pos) {
        Long id = livePlatforms.get(pos);
        if (id != null) visited.add(id);
    }

    private boolean hasOtherWool(MinecraftClient c) {
        BlockPos standing = c.player.getBlockPos().down();
        for (BlockPos pos : livePlatforms.keySet()) {
            if (!pos.equals(standing) && validTarget(c, pos)) return true;
        }
        return false;
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
        Vec3d p = c.player.getEntityPos();
        double x = Math.min(p.x - b.getX(), b.getX() + 1 - p.x);
        double z = Math.min(p.z - b.getZ(), b.getZ() + 1 - p.z);
        return Math.min(x, z);
    }

    private void loadConfig() {
        // Set all three coordinates in config/smartparkour.properties to enable fallback.
        java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("smartparkour.properties");
        try {
            if (!java.nio.file.Files.exists(file)) {
                java.nio.file.Files.writeString(file,
                    "# Set enabled=true and your destination coordinates to enable ground-only fallback\n" +
                    "enabled=false\nx=0\ny=64\nz=0\n");
            }
            java.util.Properties props = new java.util.Properties();
            try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file)) { props.load(reader); }
            homeConfigured = Boolean.parseBoolean(props.getProperty("enabled", "false"));
            homeX = Double.parseDouble(props.getProperty("x", "0"));
            homeY = Double.parseDouble(props.getProperty("y", "64"));
            homeZ = Double.parseDouble(props.getProperty("z", "0"));
        } catch (Exception e) {
            homeConfigured = false;
            System.err.println("SmartParkour: invalid config: " + e.getMessage());
        }
    }

    private void reset(MinecraftClient c) {
        target = null;
        takeoff = null;
        groundedTicks = 0;
        waitingTicks = 0;
        airborneTicks = 0;
        phase = Phase.WAIT;
        sawAirborne = false;
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
