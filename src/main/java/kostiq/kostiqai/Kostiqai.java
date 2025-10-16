package kostiq.kostiqai;

import com.google.gson.*;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * KostiqAI — difficulty curves, diversity, rollback, fair multi-player targeting.
 * v8 Changes:
 * - Replaced SPAWN action in the Nether with FIRE_UNDER for reliability.
 * - Updated AI prompt to discourage Nether spawns.
 * - Updated heuristic planner to avoid SPAWN in Nether.
 */
public class Kostiqai implements ModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("kostiqai");

    // ===== MASTER / TIMING =====
    private boolean masterEnabled = true;
    private int planningPeriodTicks = 20 * 60;
    private int tickCounter = 0;
    private int nextAllowedPlanTick = 0;

    // ---- Planner resilience ----
    private int aiFailCount = 0;
    private int aiBackoffUntilTick = 0;

    // ===== HTTP (AI) =====
    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();

    // ===== RUNTIME STATE =====
    private final Gson gson = new Gson();
    private boolean configLoaded = false;

    // Allowlist for raw commands (when model returns "commands")
    private Set<String> ALLOW = Set.of("title","playsound","tp","particle","effect","fill","setblock","summon", "kill");
    private int MAX_CMDS = 3;

    // Delayed work queue
    private List<Pending> pending = new ArrayList<>();
    private record Pending(String cmd, int dueTick) {}

    // Last pos cache (for effects that need it)
    private final Map<UUID, Vec3d> lastPos = new HashMap<>();

    // List of all action types for the heuristic planner
    private static final List<String> ALL_ACTION_TYPES = List.of(
            "SLOW", "FATIGUE", "NAUSEA", "BLIND", "LEVITATE", "LEVITATE_LONG", "HOTBAR_SHUFFLE", "SWITCH_WHILE_MINING",
            "ICE_RING", "BOUNCY_FLOOR", "HONEY_TRAP", "SAND_DRIZZLE", "FIRE_UNDER", "UNEQUIP_ARMOR", "RUBBERBAND",
            "CAGE", "SPAWN", "PISTON_SHOVE", "DROP_INVENTORY",
            "WITHER_MAYBE", "LAVA_TRAP",
            "YEET_EXPLOSION", "HYPER_SPEED", "ITEM_MAGNET", "FLOOR_PULL",
            // New in v7
            "WITHER_TEMPORARY", "BERSERK", "FLIP_VIEW", "INVENTORY_SPAM", "FORCE_RIDE"
    );

    // ===== LIMITS =====
    private static final int MAX_RADIUS = 8;
    private static final int MAX_FILL_VOLUME = 15 * 15 * 6;
    private static final int MAX_MOBS_PER_ACTION = 8;
    private static final int MAX_LAVA_TICKS = 200;
    private static final double WITHER_MIN_DIST_FROM_SPAWN = 128.0;

    // Per-player cooldowns (any action)
    private final Map<UUID, Integer> nextAllowedForPlayer = new HashMap<>();

    // ===== PROFILES =====
    private enum Mode { AUTO, MILD, SPICY, OFF }
    private static class Profile {
        Mode mode = Mode.AUTO;
        Deque<String> recent = new ArrayDeque<>(4);
        int deaths = 0;
    }
    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
    private Profile prof(UUID uuid) { return profiles.computeIfAbsent(uuid, k -> new Profile()); }
    private Profile prof(ServerPlayerEntity p) { return prof(p.getUuid()); }


    // ===== GLOBAL DIFFICULTY CURVES =====
    private enum Difficulty { LINEAR, PROGRESSIVE, BALANCED }

    private static int severityOf(String type) {
        if (type == null) return 1;
        return switch (type) {
            // Flourishes (mild) - Severity 1
            case "SLOW","FATIGUE","NAUSEA","BLIND","LEVITATE","HOTBAR_SHUFFLE","SWITCH_WHILE_MINING", "ITEM_MAGNET", "RUBBERBAND", "INVENTORY_SPAM" -> 1;
            // Visible but modest - Severity 2
            case "ICE_RING","BOUNCY_FLOOR","HONEY_TRAP","SAND_DRIZZLE","FIRE_UNDER","UNEQUIP_ARMOR", "LEVITATE_LONG", "YEET_EXPLOSION", "HYPER_SPEED", "FORCE_RIDE", "FLIP_VIEW" -> 2;
            // Spicy mid - Severity 3
            case "CAGE","SPAWN", "PISTON_SHOVE", "DROP_INVENTORY", "FLOOR_PULL", "BERSERK" -> 3;
            // Hot - Severity 4
            case "WITHER_MAYBE", "WITHER_TEMPORARY" -> 4;
            // Legendary (rare) - Severity 5
            case "LAVA_TRAP" -> 5;
            default -> 2;
        };
    }

    // Per-player, per-action cooldowns
    private final Map<UUID, Map<String,Integer>> nextAllowedByTypeForPlayer = new ConcurrentHashMap<>();
    // Global diversity window of recent action types
    private final Deque<String> recentTypes = new ArrayDeque<>();
    // NEW: fairness — last action tick per player
    private final Map<UUID, Integer> lastActionTickByPlayer = new ConcurrentHashMap<>();

    // ===== CONFIG =====
    private static class Cfg {
        // cadence
        int planningPeriodTicks = 1200;
        int cooldownTicks = 200;
        int jitterTicks = 0;

        // execution
        boolean dryRun = true;
        int maxCommandsPerCycle = 3;
        int maxActionsPerCycle = 2;
        int playerCooldownTicks = 120;
        int pendingQueueCap = 64;

        // targeting
        boolean fanoutAll = false;

        // AI control
        boolean aiEnabled = true;

        // command allowlist
        Set<String> allow = Set.of("title","playsound","tp","particle","effect","fill","setblock","summon", "kill");

        // runtime controls
        double randomness = 0.35;
        Set<String> bannedActions = new HashSet<>();
        boolean logging = true;

        // diversity & pacing
        int perActionCooldownTicks = 200;
        int globalDiversityWindow = 60;
        double maxTypeShare = 0.20;

        // global difficulty curve
        Difficulty difficulty = Difficulty.LINEAR;

        // PROGRESSIVE
        int progStageSeconds = 90;
        int progMaxStage = 5;

        // BALANCED
        int balSafeSeconds = 300; // 5 minutes
        int balNastySeconds = 120; // 2 minutes
        double balNastyBoost = 0.20;

        // OpenAI (only used if aiEnabled = true)
        OpenAI openai = new OpenAI();
        static class OpenAI {
            String model = "gpt-4o-mini";
            String baseUrl = "https://api.openai.com/v1/chat/completions";
            String apiKeyEnv = "OPENAI_API_KEY";
            int timeoutSec = 15;
        }
    }
    private Cfg cfg = new Cfg();

    // progression runtime
    private long difficultyStartTick = 0;
    private boolean inNastyWindow = false;
    private int currentStage = 1;

    // ===== ROLLBACK PERSISTENCE =====
    private static class Cell { final int x,y,z; final String blockId; Cell(int x,int y,int z,String b){this.x=x;this.y=y;this.z=z;this.blockId=b;} }
    private static class RollbackJob { String id; String worldId; List<Cell> cells; int dueTick; }
    private final Map<String, RollbackJob> rollbackJobs = new HashMap<>();
    private Path rollbackFilePath;
    private boolean rollbackLoadedOnce = false;

    private void initRollbackFilePath(MinecraftServer server) {
        if (rollbackFilePath == null) rollbackFilePath = server.getRunDirectory().resolve("config/kostiqai_pending.jsonl");
    }
    private void persistRollbackJobs() {
        if (rollbackFilePath == null) return;
        try {
            Files.createDirectories(rollbackFilePath.getParent());
            if (rollbackJobs.isEmpty()) { try { Files.deleteIfExists(rollbackFilePath); } catch (Exception ignore) {} return; }
            String out = rollbackJobs.values().stream().map(gson::toJson).collect(java.util.stream.Collectors.joining("\n"));
            Files.writeString(rollbackFilePath, out, java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) { LOG.warn("[KostiqAI] persist rollback jobs failed", e); }
    }
    private void loadRollbackJobs(MinecraftServer server) {
        initRollbackFilePath(server);
        if (!Files.exists(rollbackFilePath)) return;
        int loaded = 0;
        try {
            for (String line : Files.readAllLines(rollbackFilePath, java.nio.charset.StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                try {
                    RollbackJob j = gson.fromJson(line, RollbackJob.class);
                    if (j != null && j.id != null && j.worldId != null && j.cells != null) {
                        if (j.dueTick <= tickCounter) j.dueTick = tickCounter + 40;
                        rollbackJobs.put(j.id, j);
                        enqueuePending(new Pending("__ROLLBACK__|" + j.id, j.dueTick));
                        loaded++;
                    }
                } catch (Exception ignore) {}
            }
            if (loaded > 0) LOG.info("[KostiqAI] loaded {} rollback jobs", loaded);
        } catch (Exception e) { LOG.warn("[KostiqAI] load rollback jobs failed", e); }
    }
    private void enqueueRollback(MinecraftServer server, String worldId, List<Cell> cells, int delayTicks) {
        RollbackJob job = new RollbackJob();
        job.id = UUID.randomUUID().toString();
        job.worldId = worldId;
        job.cells = cells;
        job.dueTick = tickCounter + Math.max(1, delayTicks);
        rollbackJobs.put(job.id, job);
        persistRollbackJobs();
        enqueuePending(new Pending("__ROLLBACK__|" + job.id, job.dueTick));
    }

    // ===== OBSERVABILITY =====
    private Path obsLogPath; // logs/kostiqai.log
    private final Map<String, Integer> actionCounts = new HashMap<>();
    private final Map<String, Integer> playerCounts = new HashMap<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>(64);
    private boolean loggingEnabled = true;

    private void logActionEvent(String type, String target, boolean ok, long durationMs, JsonObject params) {
        try {
            if (!loggingEnabled || obsLogPath == null) return;
            JsonObject entry = new JsonObject();
            entry.addProperty("ts", Instant.now().toString());
            entry.addProperty("tick", tickCounter);
            entry.addProperty("type", type);
            entry.addProperty("target", target);
            entry.addProperty("ok", ok);
            entry.addProperty("duration_ms", durationMs);
            if (params != null) entry.add("params", params);

            String line = gson.toJson(entry) + "\n";
            Files.createDirectories(obsLogPath.getParent());
            Files.writeString(obsLogPath, line, java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            actionCounts.put(type, actionCounts.getOrDefault(type, 0) + 1);
            playerCounts.put(target, playerCounts.getOrDefault(target, 0) + 1);

            if (recent.size() >= 64) recent.removeFirst();
            recent.addLast(line.trim());
        } catch (Exception e) {
            LOG.warn("[KostiqAI] log write failed", e);
        }
    }

    // ===== BUDGET =====
    private int blockBudgetPerTick = 200;
    private int blockWritesThisTick = 0;
    private boolean budgetedSetBlock(ServerWorld sw, BlockPos pos, net.minecraft.block.BlockState state) {
        if (blockWritesThisTick >= blockBudgetPerTick) return false;
        sw.setBlockState(pos, state);
        blockWritesThisTick++;
        return true;
    }

    // ===== INIT / COMMANDS =====
    @Override public void onInitialize() {
        LOG.info("[KostiqAI] loaded (server-only)");
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
            var root = LiteralArgumentBuilder.<net.minecraft.server.command.ServerCommandSource>literal("kostiqai")
                    .requires(src -> src.hasPermissionLevel(2))
                    .executes(ctx -> {
                        ctx.getSource().sendFeedback(() -> Text.literal("§eUse §a/kostiqai help§e for a list of commands."), false);
                        return 1;
                    })
                    // --- Main Controls ---
                    .then(CommandManager.literal("status").executes(ctx -> {
                        int periodS   = Math.max(1, planningPeriodTicks / 20);
                        int etaS      = Math.max(0, (nextAllowedPlanTick - tickCounter)) / 20;
                        String diff = cfg.difficulty.name().toLowerCase(Locale.ROOT);
                        String stageStr = (cfg.difficulty==Difficulty.PROGRESSIVE || cfg.difficulty==Difficulty.BALANCED)
                                ? ("stage="+currentStage+(cfg.difficulty==Difficulty.BALANCED? (inNastyWindow? " (nasty)" : " (safe)") : ""))
                                : "(linear)";

                        Text status = Text.literal("").append("\n")
                                .append(Text.literal("§6--- KostiqAI Status ---§r\n"))
                                .append(Text.literal(String.format("§eEnabled:§r %s\n", masterEnabled ? "§aYES" : "§cNO")))
                                .append(Text.literal(String.format("§ePlanner:§r %s\n", cfg.aiEnabled ? "§bAI" : "§9Heuristic")))
                                .append(Text.literal(String.format("§eDifficulty:§r %s %s\n", diff, stageStr)))
                                .append(Text.literal(String.format("§eCadence:§r Every %ds | §eNext In:§r ~%ds\n", periodS, etaS)))
                                .append(Text.literal(String.format("§eDry Run:§r %s", cfg.dryRun ? "§aON" : "§cOFF")));

                        ctx.getSource().sendFeedback(() -> status, false);
                        return 1;
                    }))
                    .then(CommandManager.literal("toggle")
                            .then(CommandManager.literal("on").executes(ctx -> { masterEnabled = true;  ctx.getSource().sendFeedback(() -> Text.literal("§aKostiqAI: ENABLED"), false); return 1; }))
                            .then(CommandManager.literal("off").executes(ctx -> { masterEnabled = false; ctx.getSource().sendFeedback(() -> Text.literal("§cKostiqAI: DISABLED"), false); return 1; }))
                    )
                    .then(CommandManager.literal("trigger").executes(ctx -> {
                        if (!masterEnabled) { ctx.getSource().sendFeedback(() -> Text.literal("§cKostiqAI is DISABLED"), false); return 0; }
                        planWithAIAndMaybeExecute(ctx.getSource().getServer(), true);
                        int period = Math.max(40, planningPeriodTicks);
                        int j = (cfg.jitterTicks > 0 ? ((ctx.getSource().getServer().getOverworld()!=null?ctx.getSource().getServer().getOverworld().getRandom():net.minecraft.util.math.random.Random.create()).nextInt(cfg.jitterTicks+1)) : 0);
                        nextAllowedPlanTick = tickCounter + period + j;
                        ctx.getSource().sendFeedback(() -> Text.literal("§dKostiqAI: Forced an action plan. Next automatic plan is rescheduled."), false); return 1;
                    }))
                    // --- Configuration ---
                    .then(CommandManager.literal("config")
                            .then(CommandManager.literal("ai").then(CommandManager.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                                cfg.aiEnabled = BoolArgumentType.getBool(ctx, "value");
                                saveConfig(ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(() -> Text.literal("§6KostiqAI planner set to " + (cfg.aiEnabled ? "§bAI" : "§9Heuristic (No AI)")), false); return 1;
                            })))
                            .then(CommandManager.literal("difficulty").then(CommandManager.argument("which", StringArgumentType.word()).executes(ctx -> {
                                String w = StringArgumentType.getString(ctx, "which").toLowerCase(Locale.ROOT);
                                Difficulty d = switch (w) { case "linear"->Difficulty.LINEAR; case "progressive"->Difficulty.PROGRESSIVE; case "balanced"->Difficulty.BALANCED; default -> null; };
                                if (d == null) { ctx.getSource().sendFeedback(() -> Text.literal("§cUse: linear | progressive | balanced"), false); return 0; }
                                cfg.difficulty = d; difficultyStartTick = tickCounter; currentStage = 1; inNastyWindow = false; saveConfig(ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(() -> Text.literal("§6KostiqAI difficulty set to "+d.name().toLowerCase(Locale.ROOT)), false); return 1;
                            })))
                            .then(CommandManager.literal("period").then(CommandManager.argument("seconds", IntegerArgumentType.integer(1, 3600)).executes(ctx -> {
                                int s = clampSec(IntegerArgumentType.getInteger(ctx, "seconds"), 1, 3600);
                                planningPeriodTicks = secToTicks(s); cfg.planningPeriodTicks = planningPeriodTicks;
                                nextAllowedPlanTick = tickCounter + planningPeriodTicks;
                                saveConfig(ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(() -> Text.literal("§6KostiqAI period set to " + s + "s"), false); return 1;
                            })))
                            .then(CommandManager.literal("cooldown").then(CommandManager.argument("seconds", IntegerArgumentType.integer(2, 600)).executes(ctx -> {
                                int s = clampSec(IntegerArgumentType.getInteger(ctx, "seconds"), 2, 600);
                                cfg.cooldownTicks = secToTicks(s); saveConfig(ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(() -> Text.literal("§6KostiqAI cooldown set to " + s + "s"), false); return 1;
                            })))
                            .then(CommandManager.literal("randomness").then(CommandManager.argument("percent", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
                                int v = IntegerArgumentType.getInteger(ctx, "percent");
                                cfg.randomness = Math.max(0.0, Math.min(1.0, v / 100.0)); saveConfig(ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(() -> Text.literal("§6KostiqAI randomness set to " + v + "%"), false); return 1;
                            })))
                            .then(CommandManager.literal("dryrun").then(CommandManager.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                                cfg.dryRun = BoolArgumentType.getBool(ctx, "value"); saveConfig(ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(() -> Text.literal("§6KostiqAI dryRun mode set to " + (cfg.dryRun ? "§aON" : "§cOFF")), false); return 1;
                            })))
                    )
                    // --- Action Management ---
                    .then(CommandManager.literal("actions")
                            .then(CommandManager.literal("ban").then(CommandManager.argument("type", StringArgumentType.word()).executes(ctx -> {
                                String t = StringArgumentType.getString(ctx, "type").toUpperCase(Locale.ROOT);
                                cfg.bannedActions.add(t); saveConfig(ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(() -> Text.literal("§cKostiqAI: banned action " + t), false); return 1;
                            })))
                            .then(CommandManager.literal("allow").then(CommandManager.argument("type", StringArgumentType.word()).executes(ctx -> {
                                String t = StringArgumentType.getString(ctx, "type").toUpperCase(Locale.ROOT);
                                cfg.bannedActions.remove(t); saveConfig(ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(() -> Text.literal("§aKostiqAI: allowed action " + t), false); return 1;
                            })))
                            .then(CommandManager.literal("list").executes(ctx -> {
                                ctx.getSource().sendFeedback(() -> Text.literal("§eBanned Actions:§r " + (cfg.bannedActions.isEmpty() ? "(none)" : String.join(", ", cfg.bannedActions))), false); return 1;
                            }))
                    )
                    // --- Player Management ---
                    .then(CommandManager.literal("player")
                            .then(CommandManager.argument("name", StringArgumentType.word())
                                    .then(CommandManager.literal("mode")
                                            .then(CommandManager.argument("which", StringArgumentType.word())
                                                    .executes(ctx -> {
                                                        String name = StringArgumentType.getString(ctx, "name");
                                                        ServerPlayerEntity p = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
                                                        if (p == null) { ctx.getSource().sendFeedback(() -> Text.literal("§cPlayer not online: "+name), false); return 0; }
                                                        String w = StringArgumentType.getString(ctx, "which").toLowerCase(Locale.ROOT);
                                                        Mode m = switch (w) { case "auto"->Mode.AUTO; case "mild"->Mode.MILD; case "spicy"->Mode.SPICY; case "off"->Mode.OFF; default -> null; };
                                                        if (m == null) { ctx.getSource().sendFeedback(() -> Text.literal("§cUse: auto | mild | spicy | off"), false); return 0; }
                                                        prof(p).mode = m;
                                                        ctx.getSource().sendFeedback(() -> Text.literal(String.format("§6KostiqAI set §f%s's§6 mode to §e%s", name, m.name())), false);
                                                        return 1;
                                                    })))))
                    // --- System ---
                    .then(CommandManager.literal("system")
                            .then(CommandManager.literal("help").executes(ctx -> {
                                String msg =
                                        "§6--- KostiqAI Help ---§r\n" +
                                                "§e/kostiqai toggle <on|off>§r - Master switch for the mod.\n" +
                                                "§e/kostiqai status§r - Show a summary of the current state.\n" +
                                                "§e/kostiqai trigger§r - Force an AI action plan immediately.\n" +
                                                "§e/kostiqai config <param> <value>§r - Change settings.\n" +
                                                "  §7Params: ai, difficulty, period, cooldown, randomness, dryrun§r\n" +
                                                "§e/kostiqai player <name> mode <mode>§r - Set a player's difficulty.\n" +
                                                "  §7Modes: auto, mild, spicy, off§r\n" +
                                                "§e/kostiqai actions <ban|allow|list> [type]§r - Manage actions.\n" +
                                                "§e/kostiqai system reload§r - Reload the config file.";
                                ctx.getSource().sendFeedback(() -> Text.literal(msg), false); return 1;
                            }))
                            .then(CommandManager.literal("reload").executes(ctx -> { loadConfig(ctx.getSource().getServer()); ctx.getSource().sendFeedback(() -> Text.literal("§aKostiqAI: config reloaded"), false); return 1; }))
                    );

            dispatcher.register(root);
        });
    }

    private static String topCounts(Map<String,Integer> map, int limit) {
        if (map.isEmpty()) return "";
        return map.entrySet().stream()
                .sorted((a,b)->Integer.compare(b.getValue(),a.getValue()))
                .limit(Math.max(1, limit))
                .map(e -> e.getKey()+"="+e.getValue())
                .reduce((a,b)->a+" "+b).orElse("");
    }

    // Pick first hotbar slot (0..8) that is empty or not a tool; -1 if none.
    private static int findNonToolHotbarSlot(ServerPlayerEntity p) {
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getStack(i).isEmpty()) return i;
        for (int i = 0; i < 9; i++) { var s = inv.getStack(i); if (!s.isEmpty() && !isTool(s.getItem())) return i; }
        return -1;
    }

    private boolean enqueuePending(Pending p) {
        if (pending.size() >= Math.max(8, cfg.pendingQueueCap)) {
            LOG.warn("[KostiqAI] pending queue full ({}), dropping {}", pending.size(), p.cmd());
            return false;
        }
        pending.add(p); return true;
    }
    private boolean playerOnCooldown(ServerPlayerEntity p) {
        int now = tickCounter;
        Integer until = nextAllowedForPlayer.get(p.getUuid());
        return until != null && until > now;
    }

    private void rememberActionType(ServerPlayerEntity p, String type) {
        prof(p).recent.addLast(type);
        if (prof(p).recent.size() > 4) prof(p).recent.removeFirst();
    }
    private boolean isRepeatFor(UUID uuid, String type) {
        return prof(uuid).recent.contains(type);
    }

    // ===== TICK LOOP =====
    private void onTick(MinecraftServer server) {
        if (!configLoaded) {
            loadConfig(server);
            planningPeriodTicks = Math.max(20, cfg.planningPeriodTicks);
            configLoaded = true;
            difficultyStartTick = tickCounter;
            currentStage = 1;
            inNastyWindow = false;
        }
        updateDifficultyWindow();

        initRollbackFilePath(server);
        if (obsLogPath == null) obsLogPath = server.getRunDirectory().resolve("logs/kostiqai.log");
        if (!rollbackLoadedOnce) { loadRollbackJobs(server); rollbackLoadedOnce = true; }

        if (planningPeriodTicks < 20) planningPeriodTicks = 20;
        if (cfg.cooldownTicks < 40) cfg.cooldownTicks = 40;
        if (cfg.jitterTicks < 0) cfg.jitterTicks = 0;
        if (nextAllowedPlanTick == 0) nextAllowedPlanTick = tickCounter;

        tickCounter++;
        blockWritesThisTick = 0;

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            lastPos.put(p.getUuid(), p.getPos());
        }

        // flush delayed work
        if (!pending.isEmpty()) {
            int now = tickCounter;
            List<Pending> toRun = new ArrayList<>();
            List<Pending> waiting = new ArrayList<>();
            for (Pending p : pending) { if (p.dueTick() <= now) toRun.add(p); else waiting.add(p); }
            pending = waiting;

            for (Pending pen : toRun) {
                final String payload = pen.cmd();

                if (payload.startsWith("__ROLLBACK__|")) {
                    final String jobId = payload.substring("__ROLLBACK__|".length());
                    RollbackJob job = rollbackJobs.get(jobId);
                    if (job != null) {
                        server.execute(() -> {
                            var wid = Identifier.tryParse(job.worldId); if (wid == null) return;
                            World w = server.getWorld(worldKeyOf(wid)); if (!(w instanceof ServerWorld sw)) return;
                            for (Cell c : job.cells) {
                                var id = Identifier.tryParse(c.blockId);
                                var block = (id == null) ? null : Registries.BLOCK.get(id);
                                sw.setBlockState(new BlockPos(c.x, c.y, c.z), (block != null ? block.getDefaultState() : Blocks.AIR.getDefaultState()));
                            }
                            LOG.info("[KostiqAI] rollback applied: {} cells in {}", job.cells.size(), job.worldId);
                            rollbackJobs.remove(jobId); persistRollbackJobs();
                        });
                    }
                    continue;
                }

                if (payload.startsWith("__SET_BLOCK__|")) {
                    String[] parts = payload.split("\\|", 4);
                    Identifier wid = Identifier.tryParse(parts[1]); if (wid == null) continue;
                    String[] xyz = parts[2].split(",", 3);
                    final int x = Integer.parseInt(xyz[0]), y = Integer.parseInt(xyz[1]), z = Integer.parseInt(xyz[2]);
                    final String blockId = parts[3];
                    server.execute(() -> {
                        World w = server.getWorld(worldKeyOf(wid)); if (!(w instanceof ServerWorld sw)) return;
                        var id = Identifier.tryParse(blockId); if (id == null) return;
                        var block = Registries.BLOCK.get(id);
                        if (block != null) budgetedSetBlock(sw, new BlockPos(x,y,z), block.getDefaultState());
                    });
                    continue;
                }

                if (payload.startsWith("__WATCH_MINING__|")) {
                    String[] parts = payload.split("\\|", 3);
                    String playerName = parts[1];
                    int left = Integer.parseInt(parts[2]);
                    server.execute(() -> {
                        ServerPlayerEntity p = server.getPlayerManager().getPlayer(playerName);
                        if (p != null && looksLikeMining(p)) {
                            int slot = findNonToolHotbarSlot(p);
                            if (slot >= 0) {
                                var hand = p.getStackInHand(Hand.MAIN_HAND).copy();
                                var other = p.getInventory().getStack(slot).copy();
                                p.setStackInHand(Hand.MAIN_HAND, other);
                                p.getInventory().setStack(slot, hand);
                                p.currentScreenHandler.sendContentUpdates();
                                LOG.info("[KostiqAI] SWITCH_WHILE_MINING tripped for {} (swapped with slot {})", playerName, slot);
                                return;
                            }
                        }
                        int nextLeft = left - 10;
                        if (nextLeft > 0) enqueuePending(new Pending("__WATCH_MINING__|" + playerName + "|" + nextLeft, tickCounter + 10));
                    });
                    continue;
                }

                if (payload.startsWith("__RUBBERBAND_TELEPORT__|")) {
                    String[] parts = payload.split("\\|", 5);
                    String playerName = parts[1];
                    try {
                        final double x = Double.parseDouble(parts[2]);
                        final double y = Double.parseDouble(parts[3]);
                        final double z = Double.parseDouble(parts[4]);
                        server.execute(() -> {
                            ServerPlayerEntity p = server.getPlayerManager().getPlayer(playerName);
                            if (p != null) {
                                p.requestTeleport(x, y, z);
                                String soundCmd = String.format(Locale.ROOT, "playsound minecraft:entity.enderman.teleport master @a[distance=..32] %f %f %f 1.0 1.5", p.getX(), p.getY(), p.getZ());
                                try { server.getCommandManager().getDispatcher().execute(soundCmd, server.getCommandSource()); } catch (Exception ignored) {}
                            }
                        });
                    } catch (NumberFormatException e) {
                        LOG.warn("[KostiqAI] Could not parse rubberband coordinates from: {}", payload);
                    }
                    continue;
                }

                if (payload.startsWith("__FLIP_VIEW__|")) {
                    String[] parts = payload.split("\\|", 3);
                    String playerName = parts[1];
                    int left = Integer.parseInt(parts[2]);
                    server.execute(() -> {
                        ServerPlayerEntity p = server.getPlayerManager().getPlayer(playerName);
                        if (p != null) {
                            p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(), p.getYaw(), -p.getPitch());
                        }
                    });
                    int nextLeft = left - 5;
                    if (nextLeft > 0) {
                        enqueuePending(new Pending("__FLIP_VIEW__|" + playerName + "|" + nextLeft, tickCounter + 5));
                    }
                    continue;
                }

                final String cmdText = payload;
                server.execute(() -> {
                    try {
                        int result = server.getCommandManager().getDispatcher().execute(cmdText, server.getCommandSource());
                        LOG.info(result > 0 ? "[KostiqAI] ran (delayed): {}" : "[KostiqAI] no-op (delayed): {}", cmdText);
                    } catch (Exception e) { LOG.warn("[KostiqAI] failed (delayed): {}", cmdText, e); }
                });
            }
        }

        if (!masterEnabled) return;
        if (tickCounter >= nextAllowedPlanTick) {
            if (tickCounter < aiBackoffUntilTick) return;
            planWithAIAndMaybeExecute(server, false);

            int period = Math.max(40, planningPeriodTicks);
            int cool = Math.max(0, cfg.cooldownTicks);
            int j = (cfg.jitterTicks > 0 ? ((server.getOverworld()!=null?server.getOverworld().getRandom():net.minecraft.util.math.random.Random.create()).nextInt(cfg.jitterTicks+1)) : 0);
            nextAllowedPlanTick = tickCounter + period + cool + j;
        }
    }

    // === progression calculator ===
    private void updateDifficultyWindow() {
        if (difficultyStartTick == 0) difficultyStartTick = tickCounter;
        switch (cfg.difficulty) {
            case LINEAR -> {
                currentStage = 1;
                inNastyWindow = true;
            }
            case PROGRESSIVE -> {
                int sec = (tickCounter - (int)difficultyStartTick) / 20;
                int stage = Math.max(1, Math.min(cfg.progMaxStage, (sec / Math.max(1,cfg.progStageSeconds)) + 1));
                currentStage = stage;
                inNastyWindow = true;
            }
            case BALANCED -> {
                int cycleTicks = (cfg.balSafeSeconds + cfg.balNastySeconds) * 20;
                if (cycleTicks <= 0) { currentStage = 2; inNastyWindow = true; break; }
                long elapsedTicks = tickCounter - difficultyStartTick;
                long mod = elapsedTicks % cycleTicks;
                inNastyWindow = (mod >= cfg.balSafeSeconds * 20);
                int baseStage = Math.max(1, Math.min(cfg.progMaxStage, (int)(elapsedTicks / (long)(Math.max(1, cfg.progStageSeconds * 2) * 20)) + 1));
                currentStage = Math.max(1, Math.min(cfg.progMaxStage, baseStage + (inNastyWindow ? 1 : 0)));
            }
        }
    }
    private int allowedMaxSeverityNow() {
        return switch (cfg.difficulty) {
            case LINEAR -> 3;
            case PROGRESSIVE -> Math.min(5, currentStage + 1);
            case BALANCED -> Math.min(5, currentStage + (inNastyWindow ? 1 : 0));
        };
    }

    // ===== SNAPSHOT =====
    private JsonObject snapshotPlayers(MinecraftServer server) {
        JsonArray arr = new JsonArray();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ServerWorld sw = (ServerWorld) p.getWorld();
            BlockPos posB = p.getBlockPos();

            JsonObject j = new JsonObject();
            j.addProperty("uuid", p.getUuidAsString());
            j.addProperty("name", p.getGameProfile().getName());

            String dim = sw.getRegistryKey().getValue().toString();
            j.addProperty("dimension", dim);
            j.addProperty("isNether", World.NETHER.getValue().toString().equals(dim));
            j.addProperty("isEnd", World.END.getValue().toString().equals(dim));

            j.addProperty("biome", biomeId(sw, posB));
            j.addProperty("y", p.getBlockY());
            j.addProperty("health", (int)Math.ceil(p.getHealth()));
            j.addProperty("food", p.getHungerManager().getFoodLevel());
            j.addProperty("isCreative", p.isCreative());
            j.addProperty("isSpectator", p.isSpectator());
            j.addProperty("hasElytra", hasElytra(p));
            j.addProperty("armorTier", armorTier(p));
            j.addProperty("mode", prof(p).mode.name());

            boolean sky = sw.isSkyVisible(posB.up(2));
            boolean isCave = p.getBlockY() < 48 || !sky; j.addProperty("isCave", isCave);

            int light = lightAt(sw, posB); j.addProperty("light", light);

            JsonObject pos = new JsonObject();
            pos.addProperty("x", p.getX()); pos.addProperty("y", p.getY()); pos.addProperty("z", p.getZ());
            pos.addProperty("yaw", p.getYaw()); pos.addProperty("pitch", p.getPitch());
            j.add("position", pos);

            j.addProperty("block_below", blockIdAt(sw, posB.down()));
            j.addProperty("block_above", blockIdAt(sw, posB.up()));

            var held = p.getMainHandStack();
            j.addProperty("held_item", held.isEmpty() ? "minecraft:air" : Registries.ITEM.getId(held.getItem()).toString());

            JsonArray hotbar = new JsonArray();
            for (int slot = 0; slot < 9; slot++) {
                var s = p.getInventory().getStack(slot);
                JsonObject it = new JsonObject();
                it.addProperty("slot", slot);
                it.addProperty("item", s.isEmpty() ? "minecraft:air" : Registries.ITEM.getId(s.getItem()).toString());
                it.addProperty("count", s.getCount());
                hotbar.add(it);
            }
            j.add("hotbar", hotbar);

            JsonObject nearby = nearbySummary(sw, posB, 8);
            j.add("nearby", nearby);

            int danger = dangerScore(p, sw, posB, isCave, light, nearby.get("hostiles").getAsInt());
            j.addProperty("dangerScore", danger);

            arr.add(j);
        }
        JsonObject root = new JsonObject();
        root.add("players", arr);
        root.addProperty("difficulty", cfg.difficulty.name().toLowerCase(Locale.ROOT));
        root.addProperty("stage", currentStage);
        root.addProperty("maxSeverityNow", allowedMaxSeverityNow());
        root.addProperty("balancedWindow", (cfg.difficulty==Difficulty.BALANCED) ? (inNastyWindow?"nasty":"safe") : "n/a");
        return root;
    }

    // ===== AI / HEURISTIC PLANNER =====
    private boolean previewOnce = false;

    private void planWithAIAndMaybeExecute(MinecraftServer server, boolean force) {
        if (server.getPlayerManager().getPlayerList().isEmpty()) return;

        JsonObject snapshot = snapshotPlayers(server);

        if (!cfg.aiEnabled) {
            JsonArray actions = heuristicPlan(snapshot);
            if (cfg.dryRun) LOG.info("[KostiqAI] dryRun plan (heuristic/no-AI): {}", actions);
            else runActions(server, actions, force);
            return;
        }

        String apiKey = System.getenv(cfg.openai.apiKeyEnv);
        if (apiKey == null || apiKey.isBlank()) {
            LOG.warn("[KostiqAI] No OpenAI API key in env {}. Using heuristic planner.", cfg.openai.apiKeyEnv);
            JsonArray actions = heuristicPlan(snapshot);
            if (cfg.dryRun) LOG.info("[KostiqAI] dryRun plan (heuristic/no-API-key): {}", actions);
            else runActions(server, actions, force);
            return;
        }


        String systemPrompt =
                "You plan SHORT Minecraft pranks. Output STRICT JSON ONLY as {\"actions\":[...]}. Return at most TWO items. Include a short 'reason' string.\n" +
                        "Diversity rules: Avoid repeating the same HEADLINE action for the same player in the last 3 cycles. Prefer alternates if a choice seems overused.\n" +
                        "Per-player difficulty modes: AUTO, MILD, SPICY, OFF (OFF = do not target).\n" +
                        "Global difficulty curve (from server snapshot): use 'maxSeverityNow' as an upper bound. In BALANCED mode, prefer mild actions in 'safe' windows and spicier in 'nasty' windows.\n" +
                        "Match the environment:\n" +
                        "- Caves/dark or mining: prefer SWITCH_WHILE_MINING, SPAWN (zombies, spiders), LAVA_TRAP, BERSERK.\n" +
                        "- In the Nether, `SPAWN` is unreliable; prefer actions like `FIRE_UNDER`, `LAVA_TRAP`, or `BERSERK` instead.\n" +
                        "- Elytra or cliffs: prefer PISTON_SHOVE, LEVITATE, FORCE_RIDE.\n" +
                        "- Heavy armor/high health: CAGE, ICE_RING, BOUNCY_FLOOR, HONEY_TRAP, WITHER_TEMPORARY.\n" +
                        "- Low health/no armor: SLOW, FATIGUE, BLIND, NAUSEA, HOTBAR_SHUFFLE, LEVITATE, INVENTORY_SPAM.\n" +
                        "Examples:\n" +
                        "{\"type\":\"CAGE\",\"target\":\"<name>\",\"material\":\"minecraft:glass\",\"radius\":2,\"height\":8,\"duration_ticks\":200,\"reason\":\"...\"},\n" +
                        "{\"type\":\"SPAWN\",\"target\":\"<name>\",\"entity\":\"minecraft:zombie\",\"count\":3,\"radius\":2,\"reason\":\"...\"},\n" +
                        "{\"type\":\"LAVA_TRAP\",\"target\":\"<name>\",\"duration_ticks\":80,\"reason\":\"...\"},\n" +
                        "{\"type\":\"PISTON_SHOVE\",\"target\":\"<name>\",\"dx\":6,\"dz\":0,\"up\":1.2,\"reason\":\"...\"},\n" +
                        "{\"type\":\"UNEQUIP_ARMOR\",\"target\":\"<name>\",\"reason\":\"remove protection\"},\n" +
                        "{\"type\":\"RUBBERBAND\",\"target\":\"<name>\",\"delay_ticks\":40,\"reason\":\"simulate lag spike\"}\n" +
                        "{\"type\":\"YEET_EXPLOSION\",\"target\":\"<name>\",\"power\":2.5,\"reason\":\"non-damaging knockback\"}\n" +
                        "{\"type\":\"HYPER_SPEED\",\"target\":\"<name>\",\"seconds\":8,\"amplifier\":20,\"reason\":\"uncontrollable speed\"}\n" +
                        "{\"type\":\"FLOOR_PULL\",\"target\":\"<name>\",\"depth\":5,\"duration_ticks\":100,\"reason\":\"surprise hole\"}\n" +
                        "{\"type\":\"WITHER_TEMPORARY\",\"target\":\"<name>\",\"reason\":\"15 second boss fight\"}\n" +
                        "{\"type\":\"BERSERK\",\"target\":\"<name>\",\"seconds\":10,\"reason\":\"forced combat\"}\n" +
                        "{\"type\":\"FLIP_VIEW\",\"target\":\"<name>\",\"seconds\":8,\"reason\":\"disorienting camera\"}\n" +
                        "{\"type\":\"INVENTORY_SPAM\",\"target\":\"<name>\",\"reason\":\"fill inventory with junk\"}\n" +
                        "{\"type\":\"FORCE_RIDE\",\"target\":\"<name>\",\"reason\":\"suddenly riding a mob\"}\n";

        String userPrompt =
                "Snapshot JSON below. You MAY include a 'target' name, but if omitted the server will pick fairly among players.\n" +
                        "Max items per cycle: " + Math.max(1, Math.min(2, cfg.maxActionsPerCycle)) + ". Allowed commands: " + ALLOW + ".\n" +
                        gson.toJson(snapshot);

        JsonObject body = new JsonObject();
        String model = (cfg.openai.model == null || cfg.openai.model.isBlank()) ? "gpt-4o-mini" : cfg.openai.model;
        body.addProperty("model", model);
        JsonArray messages = new JsonArray();
        JsonObject m1 = new JsonObject(); m1.addProperty("role","system"); m1.addProperty("content", systemPrompt); messages.add(m1);
        JsonObject m2 = new JsonObject(); m2.addProperty("role","user");   m2.addProperty("content",   userPrompt); messages.add(m2);
        body.add("messages", messages);
        JsonObject rf = new JsonObject(); rf.addProperty("type", "json_object"); body.add("response_format", rf);
        body.addProperty("temperature", Math.max(0.0, Math.min(1.0, cfg.randomness)));

        String url = (cfg.openai.baseUrl == null || cfg.openai.baseUrl.isBlank())
                ? "https://api.openai.com/v1/chat/completions" : cfg.openai.baseUrl;

        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(Math.max(5, cfg.openai.timeoutSec)))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        var res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());

                        if (res.statusCode() / 100 != 2) {
                            LOG.warn("[KostiqAI] OpenAI HTTP {}", res.statusCode());
                            return null;
                        }
                        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
                        if (!root.has("choices")) return null;
                        var choices = root.getAsJsonArray("choices");
                        if (choices.size() == 0) return null;
                        JsonObject choice0 = choices.get(0).getAsJsonObject();
                        JsonObject message = choice0.getAsJsonObject("message");
                        String content = message.get("content").getAsString();
                        return JsonParser.parseString(content).getAsJsonObject();

                    } catch (Exception e) {
                        LOG.warn("[KostiqAI] planning error", e);
                        return null;
                    }
                }, io)
                .thenAccept(obj -> server.execute(() -> {
                    if (obj == null) {
                        aiFailCount = Math.min(aiFailCount + 1, 8);
                        int backoffSec = (int)Math.min(60, Math.pow(2, aiFailCount));
                        aiBackoffUntilTick = tickCounter + backoffSec * 20;
                        LOG.warn("[KostiqAI] planner backoff {}s (fail#{})", backoffSec, aiFailCount);

                        JsonArray fb = heuristicPlan(snapshot);
                        if (cfg.dryRun) LOG.info("[KostiqAI] dryRun fallback plan: {}", fb); else runActions(server, fb, force);
                        return;
                    }

                    aiFailCount = 0;
                    aiBackoffUntilTick = 0;

                    JsonArray actions  = obj.has("actions")  ? obj.getAsJsonArray("actions")  : null;
                    JsonArray commands = obj.has("commands") ? obj.getAsJsonArray("commands") : null;

                    int cap = Math.max(1, Math.min(2, cfg.maxActionsPerCycle));

                    if (previewOnce) {
                        previewOnce = false;
                        LOG.info("[KostiqAI] PREVIEW plan: {}", (actions!=null?actions:commands!=null?commands:obj));
                        return;
                    }

                    if (cfg.dryRun) {
                        if (actions  != null) LOG.info("[KostiqAI] dryRun plan (actions, cap={}): {}",  cap, trimArray(actions, cap));
                        else if (commands != null) LOG.info("[KostiqAI] dryRun plan (commands, cap={}): {}", cap, trimArray(commands, cap));
                        else LOG.info("[KostiqAI] dryRun plan: {}", obj);
                        return;
                    }

                    if (actions != null) { runActions(server, trimArray(actions, cap), force); return; }
                    if (commands != null) { runCommands(server, trimArray(commands, cap)); }
                }));
    }

    private JsonArray trimArray(JsonArray in, int max) {
        JsonArray out = new JsonArray();
        int m = Math.min(max, in == null ? 0 : in.size());
        for (int i = 0; i < m; i++) out.add(in.get(i));
        return out;
    }

    // heuristic planner
    private JsonArray heuristicPlan(JsonObject snapshot) {
        JsonArray out = new JsonArray();
        JsonArray players = snapshot.getAsJsonArray("players");
        if (players == null || players.size() == 0) return out;

        // 1. Select a target fairly
        List<JsonObject> candidates = new ArrayList<>();
        for (JsonElement playerEl : players) {
            JsonObject p = playerEl.getAsJsonObject();
            if (p.get("mode").getAsString().equals("OFF")) continue;
            candidates.add(p);
        }
        if (candidates.isEmpty()) return out;

        candidates.sort(Comparator.comparingInt(p -> {
            UUID uuid = UUID.fromString(p.get("uuid").getAsString());
            return lastActionTickByPlayer.getOrDefault(uuid, 0);
        }));

        var rnd = new java.util.Random();
        int topBand = Math.max(1, candidates.size() / 2);
        JsonObject targetPlayerSnapshot = candidates.get(rnd.nextInt(topBand));
        String targetName = targetPlayerSnapshot.get("name").getAsString();
        UUID targetUuid = UUID.fromString(targetPlayerSnapshot.get("uuid").getAsString());
        Mode targetMode = Mode.valueOf(targetPlayerSnapshot.get("mode").getAsString());
        boolean isNether = targetPlayerSnapshot.get("isNether").getAsBoolean();


        // 2. Determine valid actions
        int maxSev = allowedMaxSeverityNow();
        List<String> validHeadlines = new ArrayList<>();
        List<String> validFlourishes = new ArrayList<>();

        for (String actionType : ALL_ACTION_TYPES) {
            if (cfg.bannedActions.contains(actionType)) continue;
            if (isRepeatFor(targetUuid, actionType)) continue;
            if (isNether && actionType.equals("SPAWN")) continue;

            int severity = severityOf(actionType);
            if (severity > maxSev) continue;
            if (targetMode == Mode.MILD && severity > 1) continue;
            if (targetMode == Mode.SPICY && severity > 4) continue; // SPICY allows up to 4, not 5.

            if (severity > 1) { // Headlines are severity 2+
                validHeadlines.add(actionType);
            } else { // Flourishes are severity 1
                validFlourishes.add(actionType);
            }
        }

        // 3. Select actions to perform
        String chosenHeadline = null;
        if (!validHeadlines.isEmpty()) {
            chosenHeadline = validHeadlines.get(rnd.nextInt(validHeadlines.size()));
            out.add(createActionObject(chosenHeadline, targetName, targetPlayerSnapshot));
        }

        if (!validFlourishes.isEmpty()) {
            if (rnd.nextDouble() < 0.6 || chosenHeadline == null) { // 60% chance, or 100% if no headline
                String chosenFlourish = validFlourishes.get(rnd.nextInt(validFlourishes.size()));
                if (!chosenFlourish.equals(chosenHeadline)) {
                    out.add(createActionObject(chosenFlourish, targetName, targetPlayerSnapshot));
                }
            }
        }

        return trimArray(out, cfg.maxActionsPerCycle);
    }

    private JsonObject createActionObject(String type, String targetName, JsonObject playerSnapshot) {
        JsonObject action = obj("type", type, "target", targetName, "reason", "Heuristic Planner");
        var rnd = new java.util.Random();

        switch (type) {
            case "CAGE" -> { action.addProperty("material", "minecraft:glass"); action.addProperty("radius", 2); action.addProperty("height", 8); action.addProperty("duration_ticks", 200); }
            case "SPAWN" -> { action.addProperty("entity", "random"); action.addProperty("count", 2 + rnd.nextInt(2)); action.addProperty("radius", 3); }
            case "LAVA_TRAP" -> { action.addProperty("duration_ticks", 80); }
            case "SLOW" -> { action.addProperty("seconds", 10 + rnd.nextInt(10)); action.addProperty("amplifier", 1); }
            case "FATIGUE" -> { action.addProperty("seconds", 12 + rnd.nextInt(10)); action.addProperty("amplifier", 1); }
            case "NAUSEA" -> { action.addProperty("seconds", 8 + rnd.nextInt(8)); }
            case "SWITCH_WHILE_MINING" -> { action.addProperty("watch_seconds", 6); }
            case "BLIND" -> { action.addProperty("seconds", 5 + rnd.nextInt(5)); }
            case "WITHER_MAYBE" -> { action.addProperty("chance", 0.02); }
            case "HOTBAR_SHUFFLE", "UNEQUIP_ARMOR", "DROP_INVENTORY", "WITHER_TEMPORARY", "INVENTORY_SPAM", "FORCE_RIDE" -> {} // no params
            case "LEVITATE", "LEVITATE_LONG" -> { action.addProperty("seconds", type.equals("LEVITATE") ? 3 + rnd.nextInt(3) : 10 + rnd.nextInt(5)); }
            case "BOUNCY_FLOOR", "HONEY_TRAP", "SAND_DRIZZLE", "FIRE_UNDER" -> { action.addProperty("duration_ticks", 120); }
            case "ICE_RING" -> { action.addProperty("radius", 5); action.addProperty("duration_ticks", 400); }
            case "PISTON_SHOVE" -> { action.addProperty("dx", rnd.nextInt(13) - 6); action.addProperty("dz", rnd.nextInt(13) - 6); action.addProperty("up", 0.8 + rnd.nextDouble()); }
            case "RUBBERBAND" -> { action.addProperty("delay_ticks", 20 + rnd.nextInt(40));}
            case "YEET_EXPLOSION" -> { action.addProperty("power", 1.5 + rnd.nextDouble() * 1.5); }
            case "HYPER_SPEED" -> { action.addProperty("seconds", 6 + rnd.nextInt(5)); action.addProperty("amplifier", 20 + rnd.nextInt(15)); }
            case "ITEM_MAGNET" -> { action.addProperty("radius", 10 + rnd.nextInt(10)); }
            case "FLOOR_PULL" -> { action.addProperty("depth", 5); action.addProperty("duration_ticks", 100); }
            case "BERSERK" -> { action.addProperty("seconds", 10); }
            case "FLIP_VIEW" -> { action.addProperty("seconds", 8); }
        }
        return action;
    }

    private JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i=0;i<kv.length;i+=2) {
            String k = String.valueOf(kv[i]);
            Object v = kv[i+1];
            if (v instanceof Number n) o.addProperty(k, n);
            else if (v instanceof Boolean b) o.addProperty(k, b);
            else o.addProperty(k, String.valueOf(v));
        }
        return o;
    }

    // ===== COMMAND EXEC =====
    private void runCommands(MinecraftServer server, JsonArray commands) {
        int ran = 0;
        for (var el : commands) {
            if (ran >= MAX_CMDS) break;
            JsonObject obj = el.getAsJsonObject();
            String c = obj.get("command").getAsString().trim();
            if (c.isBlank()) continue;

            String base = c.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (!ALLOW.contains(base)) { LOG.info("[KostiqAI] blocked (not allowlisted): {}", c); continue; }

            int delay = obj.has("delay") ? Math.max(0, obj.get("delay").getAsInt()) : 0;

            if (c.contains("{TARGET}")) {
                var online = server.getPlayerManager().getPlayerList();
                if (online.isEmpty()) { LOG.info("[KostiqAI] no players for command: {}", c); continue; }
                var rnd = server.getOverworld()!=null ? server.getOverworld().getRandom() : net.minecraft.util.math.random.Random.create();
                String name = online.get(rnd.nextInt(online.size())).getGameProfile().getName();
                c = c.replace("{TARGET}", name);
            }

            if (delay > 0) {
                enqueuePending(new Pending(c, tickCounter + delay));
                LOG.info("[KostiqAI] scheduled in {}t: {}", delay, c);
            } else {
                final String cmdText = c;
                server.execute(() -> {
                    try {
                        int result = server.getCommandManager().getDispatcher().execute(cmdText, server.getCommandSource());
                        LOG.info(result > 0 ? "[KostiqAI] ran: {}" : "[KostiqAI] no-op: {}", cmdText);
                    } catch (Exception e) { LOG.warn("[KostiqAI] failed: {}", cmdText, e); }
                });
            }
            ran++;
        }
    }

    // ===== SAFE JSON + CLAMP HELPERS =====
    private static String optString(JsonObject o, String key, String def) { if (o==null||key==null) return def; JsonElement e=o.get(key); return (e!=null && !e.isJsonNull())? e.getAsString():def; }
    private static int optInt(JsonObject o, String key, int def)       { if (o==null||key==null) return def; JsonElement e=o.get(key); return (e!=null && !e.isJsonNull())? e.getAsInt():def; }
    private static double optDouble(JsonObject o, String key, double def){ if (o==null||key==null) return def; JsonElement e=o.get(key); return (e!=null && !e.isJsonNull())? e.getAsDouble():def; }
    private int clampInt(JsonObject o, String key, int def, int min, int max) { int v = optInt(o, key, def); if (v < min) v = min; if (v > max) v = max; return v; }
    private double clampDouble(JsonObject o, String key, double def, double min, double max) { double v = optDouble(o, key, def); if (Double.isNaN(v)) v = def; if (v < min) v = min; if (v > max) v = max; return v; }
    private static int secToTicks(int sec) { return Math.max(1, sec) * 20; }
    private static int clampSec(int sec, int min, int max) { if (sec < min) sec = min; if (sec > max) sec = max; return sec; }

    // ===== ACTIONS =====
    private void runActions(MinecraftServer server, JsonArray actions, boolean force) {
        if (actions == null || actions.size() == 0) return;

        Set<String> usedTypesThisCycle = new HashSet<>();

        int ran = 0;
        for (var el : actions) {
            if (ran >= cfg.maxActionsPerCycle) break;
            if (el == null || !el.isJsonObject()) { LOG.info("[KostiqAI] skip: non-object action {}", el); continue; }

            JsonObject a = el.getAsJsonObject();

            String type = optString(a, "type", "").trim();
            if (type.isEmpty()) { LOG.info("[KostiqAI] skip: missing 'type' in {}", a); continue; }

            if (cfg.bannedActions.contains(type.toUpperCase(Locale.ROOT))) {
                LOG.info("[KostiqAI] action '{}' banned; skipping", type);
                continue;
            }

            int maxSev = allowedMaxSeverityNow();
            int sev = severityOf(type);
            if (sev > maxSev) {
                String alt = pickAlternateAtSeverity(type, maxSev, null);
                if (alt != null) {
                    LOG.debug("[KostiqAI] downshifting {}(sev{}) -> {}(≤{})", type, sev, alt, maxSev);
                    type = alt;
                } else continue;
            }

            if (usedTypesThisCycle.contains(type)) {
                String alt = pickAlternateAtSeverity(type, allowedMaxSeverityNow(), null);
                if (alt != null) type = alt;
            }
            usedTypesThisCycle.add(type);

            String requestedTarget = optString(a, "target", "").trim();

            // ==== fair target choice ====
            List<ServerPlayerEntity> online = server.getPlayerManager().getPlayerList();
            if (online.isEmpty()) { LOG.info("[KostiqAI] no players online"); continue; }

            List<ServerPlayerEntity> candidates = new ArrayList<>();
            for (ServerPlayerEntity pl : online) {
                Profile pr0 = prof(pl);
                if (pr0.mode == Mode.OFF) continue;
                if (!force && playerOnCooldown(pl)) continue;
                candidates.add(pl);
            }
            if (candidates.isEmpty()) candidates.addAll(online);

            candidates.sort(Comparator.comparingInt(pl -> lastActionTickByPlayer.getOrDefault(pl.getUuid(), 0)));
            net.minecraft.util.math.random.Random rnd = (server.getOverworld()!=null?server.getOverworld().getRandom():net.minecraft.util.math.random.Random.create());
            int topBand = Math.max(1, candidates.size() / 2);

            ServerPlayerEntity chosen;
            if (!requestedTarget.isEmpty()) {
                chosen = server.getPlayerManager().getPlayer(requestedTarget);
                if (chosen == null) chosen = candidates.get(rnd.nextInt(topBand));
            } else {
                chosen = candidates.get(rnd.nextInt(topBand));
            }

            List<ServerPlayerEntity> targets = new ArrayList<>();
            if (cfg.fanoutAll) targets.addAll(candidates); else targets.add(chosen);

            for (ServerPlayerEntity p : targets) {
                if (p == null) continue;
                String name = p.getGameProfile().getName();
                if (!force && playerOnCooldown(p)) { LOG.debug("[KostiqAI] {} on cooldown", name); continue; }

                long t0 = System.nanoTime();
                boolean ok = true;
                JsonObject paramsForLog = new JsonObject();

                Profile pr = prof(p);
                if (pr.mode == Mode.OFF) { LOG.debug("[KostiqAI] {} is OFF, skipping {}", name, type); continue; }

                boolean isRepeat = isRepeatFor(p.getUuid(), type);
                if (isRepeat && !type.equals("CAGE") && !type.equals("SPAWN") && !type.equals("LAVA_TRAP")) {
                    LOG.debug("[KostiqAI] {} recently had {}, skipping minor repeat", name, type);
                    continue;
                }

                if (playerTypeOnCooldown(p, type)) {
                    String alt = pickAlternate(type, pr);
                    if (alt != null) type = alt; else continue;
                }
                if (overusedGlobally(type)) {
                    String alt = pickAlternate(type, pr);
                    if (alt != null) type = alt; else continue;
                }

                try {
                    String reason = optString(a, "reason", "");
                    if (!reason.isBlank()) paramsForLog.addProperty("reason", reason);
                    switch (type) {
                        case "CAGE" -> {
                            String mat = optString(a, "material", "minecraft:glass");
                            int radius = clampInt(a, "radius", 2, 1, 3);
                            int height = clampInt(a, "height", 8, 5, 10);
                            int dur = clampInt(a, "duration_ticks", 200, 20, 20*30);
                            paramsForLog.addProperty("material", mat); paramsForLog.addProperty("radius", radius); paramsForLog.addProperty("height", height); paramsForLog.addProperty("duration_ticks", dur);
                            doCage(server, name, mat, radius, dur, height);
                        }
                        case "SPAWN" -> {
                            String ent = optString(a, "entity", "random");
                            int count = clampInt(a, "count", 1, 1, MAX_MOBS_PER_ACTION);
                            int radius = clampInt(a, "radius", 2, 0, MAX_RADIUS);
                            paramsForLog.addProperty("entity", ent); paramsForLog.addProperty("count", count); paramsForLog.addProperty("radius", radius);
                            doSpawn(server, name, ent, count, radius);
                        }
                        case "LAVA_TRAP" -> {
                            int dur = clampInt(a, "duration_ticks", 100, 20, MAX_LAVA_TICKS);
                            paramsForLog.addProperty("duration_ticks", dur);
                            doLavaTrap(server, name, dur);
                        }
                        case "SLOW" -> {
                            int secs = clampInt(a, "seconds", 8, 2, 30);
                            int amp  = clampInt(a, "amplifier", 0, 0, 2);
                            paramsForLog.addProperty("seconds", secs); paramsForLog.addProperty("amplifier", amp);
                            doSlow(server, name, secs, amp);
                        }
                        case "FATIGUE" -> {
                            int secs = clampInt(a, "seconds", 10, 3, 40);
                            int amp  = clampInt(a, "amplifier", 0, 0, 2);
                            paramsForLog.addProperty("seconds", secs); paramsForLog.addProperty("amplifier", amp);
                            doFatigue(server, name, secs, amp);
                        }
                        case "NAUSEA" -> {
                            int secs = clampInt(a, "seconds", 10, 2, 25);
                            paramsForLog.addProperty("seconds", secs);
                            doNausea(server, name, secs);
                        }
                        case "SWITCH_WHILE_MINING" -> {
                            int watch = clampInt(a, "watch_seconds", 6, 2, 10);
                            paramsForLog.addProperty("watch_seconds", watch);
                            doSwitchWhileMining(server, name, watch);
                        }
                        case "BLIND" -> {
                            int secs = clampInt(a, "seconds", 6, 1, 15);
                            paramsForLog.addProperty("seconds", secs);
                            doBlind(server, name, secs);
                        }
                        case "WITHER_MAYBE" -> {
                            double chance = clampDouble(a, "chance", 0.02, 0.0, 0.05);
                            paramsForLog.addProperty("chance", chance);
                            doWitherMaybe(server, name, chance);
                        }
                        case "HOTBAR_SHUFFLE" -> doHotbarShuffle(server, name);
                        case "LEVITATE", "LEVITATE_LONG" -> {
                            int secs = (type.equals("LEVITATE_LONG")) ? clampInt(a, "seconds", 10, 5, 20) : clampInt(a, "seconds", 3, 1, 10);
                            paramsForLog.addProperty("seconds", secs);
                            doLevitate(server, name, secs);
                        }
                        case "BOUNCY_FLOOR" -> {
                            int dur = clampInt(a, "duration_ticks", 120, 40, 20*10);
                            paramsForLog.addProperty("duration_ticks", dur);
                            doBouncyFloor(server, name, dur);
                        }
                        case "HONEY_TRAP" -> {
                            int dur = clampInt(a, "duration_ticks", 120, 40, 20*10);
                            paramsForLog.addProperty("duration_ticks", dur);
                            doHoneyTrap(server, name, dur);
                        }
                        case "PISTON_SHOVE" -> {
                            int dx = clampInt(a, "dx", 4, -8, 8);
                            int dz = clampInt(a, "dz", 0, -8, 8);
                            double up = clampDouble(a, "up", 1.0, 0.2, 1.5);
                            paramsForLog.addProperty("dx", dx); paramsForLog.addProperty("dz", dz); paramsForLog.addProperty("up", up);
                            doPistonShove(server, name, dx, dz, up);
                        }
                        case "ICE_RING" -> {
                            int r = clampInt(a, "radius", 5, 2, 8);
                            int dur = clampInt(a, "duration_ticks", 400, 100, 20*30);
                            paramsForLog.addProperty("radius", r); paramsForLog.addProperty("duration_ticks", dur);
                            doIceRing(server, name, r, dur);
                        }
                        case "SAND_DRIZZLE" -> {
                            int dur = clampInt(a, "duration_ticks", 100, 40, 20*10);
                            paramsForLog.addProperty("duration_ticks", dur);
                            doSandDrizzle(server, name, dur);
                        }
                        case "DROP_INVENTORY" -> {
                            doDropInventory(server, name);
                        }
                        case "FIRE_UNDER" -> {
                            int dur = clampInt(a, "duration_ticks", 100, 20, 20*10);
                            paramsForLog.addProperty("duration_ticks", dur);
                            doFireUnder(server, name, dur);
                        }
                        case "UNEQUIP_ARMOR" -> {
                            paramsForLog.addProperty("dropIfFull", true);
                            doUnequipArmor(server, name, true);
                        }
                        case "RUBBERBAND" -> {
                            int delay = clampInt(a, "delay_ticks", 40, 10, 80);
                            paramsForLog.addProperty("delay_ticks", delay);
                            doRubberband(server, name, delay);
                        }
                        case "YEET_EXPLOSION" -> {
                            float power = (float)clampDouble(a, "power", 2.0, 1.0, 4.0);
                            paramsForLog.addProperty("power", power);
                            doYeetExplosion(server, name, power);
                        }
                        case "HYPER_SPEED" -> {
                            int secs = clampInt(a, "seconds", 8, 4, 15);
                            int amp = clampInt(a, "amplifier", 25, 20, 40);
                            paramsForLog.addProperty("seconds", secs); paramsForLog.addProperty("amplifier", amp);
                            doHyperSpeed(server, name, secs, amp);
                        }
                        case "ITEM_MAGNET" -> {
                            int r = clampInt(a, "radius", 15, 5, 25);
                            paramsForLog.addProperty("radius", r);
                            doItemMagnet(server, name, r);
                        }
                        case "FLOOR_PULL" -> {
                            int depth = clampInt(a, "depth", 5, 2, 8);
                            int dur = clampInt(a, "duration_ticks", 100, 20, 200);
                            paramsForLog.addProperty("depth", depth); paramsForLog.addProperty("duration_ticks", dur);
                            doFloorPull(server, name, depth, dur);
                        }
                        case "WITHER_TEMPORARY" -> {
                            doWitherTemporary(server, name);
                        }
                        case "BERSERK" -> {
                            int secs = clampInt(a, "seconds", 10, 5, 20);
                            paramsForLog.addProperty("seconds", secs);
                            doBerserk(server, name, secs);
                        }
                        case "FLIP_VIEW" -> {
                            int secs = clampInt(a, "seconds", 8, 4, 15);
                            paramsForLog.addProperty("seconds", secs);
                            doFlipView(server, name, secs);
                        }
                        case "INVENTORY_SPAM" -> {
                            doInventorySpam(server, name);
                        }
                        case "FORCE_RIDE" -> {
                            doForceRide(server, name);
                        }
                        default -> { LOG.info("[KostiqAI] unknown action type: {}", type); ok = false; }
                    }
                } catch (Exception ex) {
                    ok = false;
                    LOG.warn("[KostiqAI] action '{}' for {} failed with {}", type, name, ex.toString());
                } finally {
                    if (ok) {
                        rememberActionType(p, type);
                        rememberGlobalType(type);
                        armPlayerTypeCooldown(p, type);
                        lastActionTickByPlayer.put(p.getUuid(), tickCounter); // fairness
                    }
                    long ms = Math.max(0, (System.nanoTime() - t0) / 1_000_000);
                    logActionEvent(type, name, ok, ms, paramsForLog);
                }

                nextAllowedForPlayer.put(p.getUuid(), tickCounter + Math.max(40, cfg.playerCooldownTicks));
            }
            ran++;
        }
    }

    // ---- Mild effects
    private void doSlow(MinecraftServer server, String name, int seconds, int amp) {
        final int secs = seconds, a = Math.max(0, Math.min(2, amp));
        server.execute(() -> {
            var p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, secs * 20, a, true, true));
            LOG.info("[KostiqAI] slowness {}s amp{}", secs, a);
        });
    }
    private void doFatigue(MinecraftServer server, String name, int seconds, int amp) {
        final int secs = seconds, a = Math.max(0, Math.min(2, amp));
        server.execute(() -> {
            var p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, secs * 20, a, true, true));
            LOG.info("[KostiqAI] mining fatigue {}s amp{}", secs, a);
        });
    }
    private void doNausea(MinecraftServer server, String name, int seconds) {
        final int secs = seconds;
        server.execute(() -> {
            var p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, secs * 20, 0, true, true));
            LOG.info("[KostiqAI] nausea {}s", secs);
        });
    }

    // ---- Watch & switch while mining
    private void doSwitchWhileMining(MinecraftServer server, String name, int watchSeconds) {
        final int ticks = Math.max(20, Math.min(20*10, watchSeconds * 20));
        enqueuePending(new Pending("__WATCH_MINING__|" + name + "|" + ticks, tickCounter + 1));
        LOG.info("[KostiqAI] watching {} for mining ({}s)", name, watchSeconds);
    }

    // ---- Physics shove
    private void doPistonShove(MinecraftServer server, String name, int dx, int dz, double up) {
        final int dx0 = dx, dz0 = dz; final double up0 = up;
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            double mag = Math.sqrt(dx0*dx0 + dz0*dz0); if (mag < 0.1) return;
            double scale = 2.5;
            double vx = (dx0 / mag) * scale;
            double vz = (dz0 / mag) * scale;
            p.addVelocity(vx, up0, vz);
            p.velocityModified = true;
            LOG.info("[KostiqAI] piston shove {} vel=({},{},{})", name, String.format(Locale.ROOT,"%.2f",vx), String.format(Locale.ROOT,"%.2f",up0), String.format(Locale.ROOT,"%.2f",vz));
        });
    }

    // ---- ICE ring (rollback)
    private void doIceRing(MinecraftServer server, String name, int radius, int duration) {
        final int r = radius, dur = duration;
        server.execute(() -> {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(name); if (pl == null) return;
            ServerWorld sw = (ServerWorld) pl.getWorld();
            BlockPos c = pl.getBlockPos().down();
            ArrayList<Cell> cells = new ArrayList<>();
            for (int dx=-r; dx<=r; dx++) for (int dz=-r; dz<=r; dz++) {
                if (Math.sqrt(dx*dx + dz*dz) > r) continue;
                BlockPos p = c.add(dx, 0, dz);
                String prev = Registries.BLOCK.getId(sw.getBlockState(p).getBlock()).toString();
                cells.add(new Cell(p.getX(), p.getY(), p.getZ(), prev));
                budgetedSetBlock(sw, p, Blocks.ICE.getDefaultState());
            }
            enqueueRollback(server, sw.getRegistryKey().getValue().toString(), cells, dur);
            LOG.info("[KostiqAI] ice ring for {} r={} dur={}t", name, r, dur);
        });
    }

    // remember/overuse/cooldowns
    private void rememberGlobalType(String type) {
        if (type == null || type.isBlank()) return;
        recentTypes.addLast(type);
        while (recentTypes.size() > Math.max(10, cfg.globalDiversityWindow)) recentTypes.removeFirst();
    }
    private boolean overusedGlobally(String type) {
        if (recentTypes.isEmpty()) return false;
        int count = 0; for (String t : recentTypes) if (t.equals(type)) count++;
        double share = (double) count / (double) recentTypes.size();
        return share > Math.max(0.10, Math.min(0.90, cfg.maxTypeShare));
    }
    private boolean playerTypeOnCooldown(ServerPlayerEntity p, String type) {
        Map<String,Integer> m = nextAllowedByTypeForPlayer.get(p.getUuid());
        if (m == null) return false;
        Integer until = m.get(type);
        return until != null && until > tickCounter;
    }
    private void armPlayerTypeCooldown(ServerPlayerEntity p, String type) {
        nextAllowedByTypeForPlayer.computeIfAbsent(p.getUuid(), k -> new ConcurrentHashMap<>())
                .put(type, tickCounter + Math.max(40, cfg.perActionCooldownTicks));
    }

    private String pickAlternate(String type, Profile pr) {
        final String[] HEADLINES = {"CAGE","SPAWN","LAVA_TRAP","ICE_RING","SAND_DRIZZLE","BOUNCY_FLOOR","HONEY_TRAP","PISTON_SHOVE","DROP_INVENTORY","FIRE_UNDER","UNEQUIP_ARMOR"};
        final String[] FLOURISH  = {"SLOW","FATIGUE","NAUSEA","BLIND","LEVITATE","LEVITATE_LONG","HOTBAR_SHUFFLE","SWITCH_WHILE_MINING","RUBBERBAND"};
        java.util.List<String> pool = new java.util.ArrayList<>();
        boolean headline = java.util.Arrays.asList(HEADLINES).contains(type);
        if (headline) java.util.Collections.addAll(pool, HEADLINES); else java.util.Collections.addAll(pool, FLOURISH);
        pool.remove(type);
        pool.removeIf(t -> cfg.bannedActions.contains(t));
        if (pr.mode == Mode.MILD) {
            pool.removeAll(java.util.Arrays.asList("LAVA_TRAP","SAND_DRIZZLE","PISTON_SHOVE","SPAWN","CAGE","DROP_INVENTORY","FIRE_UNDER","UNEQUIP_ARMOR"));
        }
        if (pool.isEmpty()) return null;
        for (String t : pr.recent) pool.remove(t);
        java.util.Random r = new java.util.Random();
        return pool.get(r.nextInt(pool.size()));
    }
    private String pickAlternateAtSeverity(String fromType, int maxSev, Profile pr) {
        java.util.List<String> pool = new java.util.ArrayList<>();
        for (String t : ALL_ACTION_TYPES) if (severityOf(t) <= maxSev && !t.equals(fromType)) pool.add(t);
        pool.removeIf(t -> cfg.bannedActions.contains(t));
        if (pr != null && pr.mode == Mode.MILD) {
            pool.removeIf(t -> severityOf(t) > 1);
        }
        if (cfg.difficulty == Difficulty.BALANCED && !inNastyWindow) {
            pool.removeIf(t -> severityOf(t) >= 4);
        }
        if (pool.isEmpty()) return null;
        java.util.Random r = new java.util.Random();
        return pool.get(r.nextInt(pool.size()));
    }

    // ---- SAND drizzle (rollback)
    private void doSandDrizzle(MinecraftServer server, String name, int duration) {
        final int dur = duration;
        server.execute(() -> {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(name); if (pl == null) return;
            ServerWorld sw = (ServerWorld) pl.getWorld();
            BlockPos feet = pl.getBlockPos();
            ArrayList<Cell> cells = new ArrayList<>();

            // Create 3 layers of sand above the player
            for (int yOff = 3; yOff <= 5; yOff++) { // Layers at Y+3, Y+4, Y+5 relative to player feet
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos currentPos = feet.add(dx, yOff, dz);
                        if (sw.getBlockState(currentPos).isAir()) {
                            cells.add(new Cell(currentPos.getX(), currentPos.getY(), currentPos.getZ(), "minecraft:air"));
                            budgetedSetBlock(sw, currentPos, Blocks.SAND.getDefaultState());
                        }
                    }
                }
            }
            if (cells.isEmpty()){
                LOG.info("[KostiqAI] sand drizzle for {} aborted (no space above player)", name);
                return;
            }

            enqueueRollback(server, sw.getRegistryKey().getValue().toString(), cells, dur);
            LOG.info("[KostiqAI] sand drizzle for {} dur={}t", name, dur);
        });
    }

    // ---- Cage (rollback), height
    private void doCage(MinecraftServer server, String name, String materialId, int r, int duration, int height) {
        final int r0 = r;
        final String mat0 = materialId;
        final int h0 = height;
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
            if (p == null) return;
            ServerWorld sw = (ServerWorld) p.getWorld();
            BlockPos base = p.getBlockPos();
            ArrayList<Cell> cells = new ArrayList<>();

            Identifier blockId = Identifier.tryParse(mat0);
            if (blockId == null) return;
            var block = Registries.BLOCK.get(blockId);
            if (block == null) return;
            var blockState = block.getDefaultState();

            // Record and build walls, floor, and ceiling
            for (int yOff = -2; yOff <= h0; yOff++) {
                for (int dx = -r0; dx <= r0; dx++) {
                    for (int dz = -r0; dz <= r0; dz++) {
                        boolean isWall = yOff > -2 && yOff < h0 && (Math.abs(dx) == r0 || Math.abs(dz) == r0);
                        boolean isFloor = yOff == -2;
                        boolean isCeiling = yOff == h0;

                        if (isWall || isFloor || isCeiling) {
                            BlockPos currentPos = base.add(dx, yOff, dz);
                            cells.add(new Cell(currentPos.getX(), currentPos.getY(), currentPos.getZ(), Registries.BLOCK.getId(sw.getBlockState(currentPos).getBlock()).toString()));
                            budgetedSetBlock(sw, currentPos, blockState);
                        }
                    }
                }
            }

            LOG.info("[KostiqAI] cage built r={} h={} material={} for {}", r0, h0, mat0, name);

            if (duration > 0) {
                enqueueRollback(server, sw.getRegistryKey().getValue().toString(), cells, duration);
            }
        });
    }

    private void setIfAir(ServerWorld w, BlockPos pos, String materialId) {
        if (w.getBlockState(pos).isAir()) {
            Identifier id = Identifier.tryParse(materialId); if (id == null) return;
            var block = Registries.BLOCK.get(id); if (block != null) budgetedSetBlock(w, pos, block.getDefaultState());
        }
    }

    private void doSwapHandWithSlot(MinecraftServer server, String name, int slot) {
        final int slot0 = Math.max(0, Math.min(35, slot));
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            var inv = p.getInventory();
            var hand = p.getStackInHand(Hand.MAIN_HAND).copy();
            var other = inv.getStack(slot0).copy();
            p.setStackInHand(Hand.MAIN_HAND, other);
            inv.setStack(slot0, hand);
            p.currentScreenHandler.sendContentUpdates();
            LOG.info("[KostiqAI] swapped hand with slot {} for {}", slot0, name);
        });
    }

    private static final List<String> RANDOM_HOSTILES = List.of("minecraft:zombie", "minecraft:skeleton", "minecraft:spider", "minecraft:creeper");
    private static final List<String> RANDOM_NETHER_HOSTILES = List.of("minecraft:zombified_piglin", "minecraft:piglin", "minecraft:magma_cube", "minecraft:blaze");

    private void doSpawn(MinecraftServer server, String name, String entityId, int count, int radius) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            ServerWorld sw = (ServerWorld) p.getWorld();
            boolean isNether = sw.getRegistryKey().equals(World.NETHER);

            if (isNether) {
                LOG.info("[KostiqAI] SPAWN triggered in Nether, substituting with FIRE_UNDER for reliability.");
                doFireUnder(server, name, 120); // Substitute with a 6-second fire trap.
                return;
            }

            final String entStr;
            if (entityId.equalsIgnoreCase("random")) {
                entStr = RANDOM_HOSTILES.get(sw.getRandom().nextInt(RANDOM_HOSTILES.size()));
            } else {
                entStr = entityId;
            }

            if (EntityType.get(entStr).isEmpty()) {
                LOG.warn("[KostiqAI] invalid entity for SPAWN: {}", entStr);
                return;
            }

            int spawnedCount = 0;
            for (int i = 0; i < count; i++) {
                for (int attempt = 0; attempt < 15; attempt++) { // try 15 times to find a spot for each mob
                    int dx = sw.getRandom().nextInt(radius * 2 + 1) - radius;
                    int dz = sw.getRandom().nextInt(radius * 2 + 1) - radius;
                    BlockPos basePos = p.getBlockPos().add(dx, 0, dz);

                    Optional<BlockPos> safePos = findSafeSpawnLocation(sw, basePos);

                    if (safePos.isPresent()) {
                        BlockPos finalPos = safePos.get();
                        String cmd = String.format(Locale.ROOT,"summon %s %d %d %d", entStr, finalPos.getX(), finalPos.getY(), finalPos.getZ());
                        try {
                            server.getCommandManager().getDispatcher().execute(cmd, server.getCommandSource());
                            spawnedCount++;
                        }
                        catch (Exception e) { LOG.warn("[KostiqAI] summon failed: {}", cmd, e); }
                        break; // Move to the next mob
                    }
                }
            }
            if (spawnedCount > 0) {
                LOG.info("[KostiqAI] spawned {} x {} around {}", spawnedCount, entStr, name);
            } else {
                LOG.info("[KostiqAI] failed to find safe spawn locations for {} around {}", entStr, name);
            }
        });
    }

    private Optional<BlockPos> findSafeSpawnLocation(ServerWorld world, BlockPos center) {
        for (int yOff = 5; yOff >= -5; yOff--) {
            BlockPos currentPos = center.up(yOff);
            if (world.getBlockState(currentPos).isOf(Blocks.NETHER_PORTAL)) continue;

            BlockPos below = currentPos.down();
            BlockState belowState = world.getBlockState(below);
            if (belowState.isOf(Blocks.NETHER_PORTAL)) continue;

            boolean isSolidBelow = belowState.isSolidBlock(world, below);
            boolean isPassableCurrent = !world.getBlockState(currentPos).blocksMovement();
            boolean isPassableAbove = !world.getBlockState(currentPos.up()).blocksMovement();

            if (isSolidBelow && isPassableCurrent && isPassableAbove) {
                return Optional.of(currentPos);
            }
        }
        return Optional.empty();
    }


    private void doLavaTrap(MinecraftServer server, String name, int duration) {
        final int dur = Math.min(duration, MAX_LAVA_TICKS);
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            ServerWorld sw = (ServerWorld) p.getWorld(); BlockPos pos = p.getBlockPos().down();
            String prevId = Registries.BLOCK.getId(sw.getBlockState(pos).getBlock()).toString();
            List<Cell> cells = List.of(new Cell(pos.getX(), pos.getY(), pos.getZ(), prevId));
            budgetedSetBlock(sw, pos, Blocks.LAVA.getDefaultState());
            LOG.info("[KostiqAI] lava under {} for {}t", name, dur);
            enqueueRollback(server, sw.getRegistryKey().getValue().toString(), cells, dur);
        });
    }

    private void doBlind(MinecraftServer server, String name, int seconds) {
        final int secs = seconds;
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, secs * 20, 0, true, true));
            LOG.info("[KostiqAI] blind {}s", secs);
        });
    }

    private void doHotbarShuffle(MinecraftServer server, String name) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            var inv = p.getInventory();
            for (int i = 0; i < 9; i++) {
                int j = ((ServerWorld) p.getWorld()).getRandom().nextInt(9);
                ItemStack a = inv.getStack(i).copy();
                ItemStack b = inv.getStack(j).copy();
                inv.setStack(i, b);
                inv.setStack(j, a);
            }
            p.currentScreenHandler.sendContentUpdates();
            LOG.info("[KostiqAI] hotbar shuffle {}", name);
        });
    }

    private void doLevitate(MinecraftServer server, String name, int seconds) {
        final int secs = seconds;
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, secs * 20, 0, true, true));
            LOG.info("[KostiqAI] levitate {}s", secs);
        });
    }

    private void doBouncyFloor(MinecraftServer server, String name, int duration) {
        final int dur = duration;
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            ServerWorld sw = (ServerWorld) p.getWorld();
            BlockPos feet = p.getBlockPos().down();
            String prev = Registries.BLOCK.getId(sw.getBlockState(feet).getBlock()).toString();
            ArrayList<Cell> cells = new ArrayList<>();
            cells.add(new Cell(feet.getX(), feet.getY(), feet.getZ(), prev));
            budgetedSetBlock(sw, feet, Blocks.SLIME_BLOCK.getDefaultState());
            enqueueRollback(server, sw.getRegistryKey().getValue().toString(), cells, dur);
            LOG.info("[KostiqAI] bouncy floor {}t for {}", dur, name);
        });
    }

    private void doHoneyTrap(MinecraftServer server, String name, int duration) {
        final int dur = duration;
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            ServerWorld sw = (ServerWorld) p.getWorld();
            BlockPos feet = p.getBlockPos();
            ArrayList<Cell> cells = new ArrayList<>();
            for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
                BlockPos pos = feet.add(dx, -1, dz);
                String prev = Registries.BLOCK.getId(sw.getBlockState(pos).getBlock()).toString();
                cells.add(new Cell(pos.getX(), pos.getY(), pos.getZ(), prev));
                budgetedSetBlock(sw, pos, Blocks.HONEY_BLOCK.getDefaultState());
            }
            enqueueRollback(server, sw.getRegistryKey().getValue().toString(), cells, dur);
            LOG.info("[KostiqAI] honey trap {}t for {}", dur, name);
        });
    }

    private void doWitherMaybe(MinecraftServer server, String name, double chance) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
            if (p == null) return;
            if (p.getWorld().getRandom().nextDouble() >= chance) return;

            Vec3d spawnPoint = p.getWorld().getSpawnPos().toCenterPos();
            if (p.getPos().squaredDistanceTo(spawnPoint) < WITHER_MIN_DIST_FROM_SPAWN * WITHER_MIN_DIST_FROM_SPAWN) {
                LOG.info("[KostiqAI] wither blocked (too close to spawn)");
                return;
            }

            Optional<BlockPos> safePos = findSafeWitherSpawnLocation((ServerWorld) p.getWorld(), p.getBlockPos().add(4, 1, 4));

            if (safePos.isPresent()) {
                BlockPos finalPos = safePos.get();
                String cmd = String.format(Locale.ROOT, "summon minecraft:wither %d %d %d", finalPos.getX(), finalPos.getY(), finalPos.getZ());
                try {
                    server.getCommandManager().getDispatcher().execute(cmd, server.getCommandSource());
                    LOG.warn("[KostiqAI] WITHER spawned near {}", name);
                } catch (Exception e) {
                    LOG.warn("[KostiqAI] wither summon failed: {}", cmd, e);
                }
            } else {
                LOG.info("[KostiqAI] wither failed for {}: could not find a safe spawn location.", name);
            }
        });
    }

    private void doDropInventory(MinecraftServer server, String name) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            var inv = p.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (!s.isEmpty()) {
                    p.dropItem(s.copy(), true);
                    inv.setStack(i, ItemStack.EMPTY);
                }
            }
            p.currentScreenHandler.sendContentUpdates();
            LOG.info("[KostiqAI] {} dropped entire inventory (no restore)", name);
        });
    }

    private void doFireUnder(MinecraftServer server, String name, int duration) {
        final int dur = duration;
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            ServerWorld sw = (ServerWorld) p.getWorld();
            BlockPos pos = p.getBlockPos();

            String prev = Registries.BLOCK.getId(sw.getBlockState(pos).getBlock()).toString();
            List<Cell> cells = List.of(new Cell(pos.getX(), pos.getY(), pos.getZ(), prev));

            BlockPos below = pos.down();
            if (!sw.getBlockState(pos).isAir() || sw.getBlockState(below).isAir()) {
                LOG.info("[KostiqAI] FIRE_UNDER aborted (not placeable) for {}", name);
                return;
            }
            budgetedSetBlock(sw, pos, Blocks.FIRE.getDefaultState());
            enqueueRollback(server, sw.getRegistryKey().getValue().toString(), cells, dur);
            LOG.info("[KostiqAI] fire under {} for {}t", name, dur);
        });
    }

    private void doUnequipArmor(MinecraftServer server, String name, boolean dropIfFull) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            var inv = p.getInventory();
            boolean any = false;

            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                ItemStack cur = p.getEquippedStack(slot);
                if (cur.isEmpty()) continue;
                ItemStack toStore = cur.copy();
                p.equipStack(slot, ItemStack.EMPTY);
                if (!inv.insertStack(toStore)) {
                    if (dropIfFull) p.dropItem(toStore, true);
                    else { p.equipStack(slot, toStore); continue; }
                }
                any = true;
            }
            if (any) {
                p.currentScreenHandler.sendContentUpdates();
                LOG.info("[KostiqAI] unequipped armor for {}", name);
            }
        });
    }

    private void doRubberband(MinecraftServer server, String name, int delayTicks) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            Vec3d pos = p.getPos();
            String payload = String.format(Locale.ROOT, "__RUBBERBAND_TELEPORT__|%s|%f|%f|%f", name, pos.x, pos.y, pos.z);
            enqueuePending(new Pending(payload, tickCounter + delayTicks));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, delayTicks + 10, 0, true, false));
            LOG.info("[KostiqAI] rubberband armed for {} in {} ticks", name, delayTicks);
        });
    }

    private void doYeetExplosion(MinecraftServer server, String name, float power) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;

            String particleCmd = String.format(Locale.ROOT, "particle minecraft:explosion_emitter %f %f %f 0 0 0 0.5 1 force", p.getX(), p.getY() + 0.5, p.getZ());
            String soundCmd = String.format(Locale.ROOT, "playsound minecraft:entity.generic.explode master @a[distance=..32] %f %f %f 2.0 1.0", p.getX(), p.getY(), p.getZ());

            try {
                server.getCommandManager().getDispatcher().execute(particleCmd, server.getCommandSource());
                server.getCommandManager().getDispatcher().execute(soundCmd, server.getCommandSource());
            } catch (Exception e) {
                LOG.warn("[KostiqAI] failed to create explosion effects via command", e);
            }

            Vec3d vel = p.getVelocity();
            Vec3d look = p.getRotationVector();
            Vec3d direction = (vel.lengthSquared() > 0.01) ? vel.normalize() : look;

            double yeetStrength = power * 0.6;
            double verticalStrength = 1.0 + (power - 1.0) * 0.4;
            p.addVelocity(direction.x * yeetStrength, verticalStrength, direction.z * yeetStrength);
            p.velocityModified = true;
            LOG.info("[KostiqAI] yeet explosion for {} with power {}", name, power);
        });
    }

    private void doHyperSpeed(MinecraftServer server, String name, int seconds, int amplifier) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, seconds * 20, amplifier, true, false));
            LOG.info("[KostiqAI] hyper speed for {} ({}s, amp {})", name, seconds, amplifier);
        });
    }

    private void doItemMagnet(MinecraftServer server, String name, int radius) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            ServerWorld sw = (ServerWorld) p.getWorld();
            Box box = new Box(p.getBlockPos()).expand(radius);
            List<ItemEntity> items = sw.getEntitiesByClass(ItemEntity.class, box, item -> true);
            for (ItemEntity item : items) {
                item.requestTeleport(p.getX(), p.getY(), p.getZ());
            }
            LOG.info("[KostiqAI] item magnet for {} pulled {} items", name, items.size());
        });
    }

    private void doFloorPull(MinecraftServer server, String name, int depth, int duration) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name); if (p == null) return;
            ServerWorld sw = (ServerWorld) p.getWorld();
            BlockPos base = p.getBlockPos();
            ArrayList<Cell> cells = new ArrayList<>();

            for (int i = 1; i <= depth; i++) {
                BlockPos currentPos = base.down(i);
                cells.add(new Cell(currentPos.getX(), currentPos.getY(), currentPos.getZ(), Registries.BLOCK.getId(sw.getBlockState(currentPos).getBlock()).toString()));
                budgetedSetBlock(sw, currentPos, Blocks.AIR.getDefaultState());
            }

            enqueueRollback(server, sw.getRegistryKey().getValue().toString(), cells, duration);
            LOG.info("[KostiqAI] floor pull for {} ({} deep)", name, depth);
        });
    }

    // ===== NEW ACTIONS (v7) =====

    private void doWitherTemporary(MinecraftServer server, String name) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
            if (p == null) return;
            Optional<BlockPos> safePos = findSafeWitherSpawnLocation((ServerWorld) p.getWorld(), p.getBlockPos().add(3, 1, 3));

            if (safePos.isPresent()) {
                BlockPos finalPos = safePos.get();
                String tag = "temp_wither_" + tickCounter;
                String summonCmd = String.format(Locale.ROOT, "summon minecraft:wither %d %d %d {Tags:[\"%s\"], Invul:100}",
                        finalPos.getX(), finalPos.getY(), finalPos.getZ(), tag);
                String killCmd = String.format("kill @e[type=minecraft:wither,tag=%s,limit=1,sort=nearest]", tag);

                try {
                    server.getCommandManager().getDispatcher().execute(summonCmd, server.getCommandSource());
                    enqueuePending(new Pending(killCmd, tickCounter + 300)); // 15 seconds
                    LOG.info("[KostiqAI] temporary wither spawned near {}", name);
                } catch (Exception e) {
                    LOG.warn("[KostiqAI] temporary wither summon failed: {}", summonCmd, e);
                }
            } else {
                LOG.info("[KostiqAI] temporary wither failed for {}: could not find a safe spawn location.", name);
            }
        });
    }

    private void doBerserk(MinecraftServer server, String name, int seconds) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
            if (p == null) return;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, seconds * 20, 1, true, true));
            doSpawn(server, name, "minecraft:silverfish", 3, 1);
            LOG.info("[KostiqAI] berserk triggered for {}", name);
        });
    }

    private void doFlipView(MinecraftServer server, String name, int seconds) {
        int durationTicks = seconds * 20;
        enqueuePending(new Pending("__FLIP_VIEW__|" + name + "|" + durationTicks, tickCounter + 5));
        LOG.info("[KostiqAI] flip view started for {} ({}s)", name, seconds);
    }

    private static final List<Item> TRASH_ITEMS = List.of(
            Items.WHEAT_SEEDS, Items.POPPY, Items.DANDELION, Items.DIRT, Items.COBBLESTONE,
            Items.ROTTEN_FLESH, Items.STRING, Items.BONE, Items.GUNPOWDER
    );

    private void doInventorySpam(MinecraftServer server, String name) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
            if (p == null) return;
            var inv = p.getInventory();
            var rnd = new Random();
            int filled = 0;
            for (int i = 0; i < 36; i++) {
                if (inv.getStack(i).isEmpty()) {
                    Item trash = TRASH_ITEMS.get(rnd.nextInt(TRASH_ITEMS.size()));
                    inv.setStack(i, new ItemStack(trash, rnd.nextInt(1, trash.getMaxCount() / 2)));
                    filled++;
                }
            }
            if (filled > 0) {
                p.currentScreenHandler.sendContentUpdates();
                LOG.info("[KostiqAI] spammed {}'s inventory with {} items", name, filled);
            }
        });
    }

    private void doForceRide(MinecraftServer server, String name) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
            if (p == null) return;
            ServerWorld sw = (ServerWorld) p.getWorld();
            Box box = new Box(p.getBlockPos()).expand(10);
            List<Entity> entities = sw.getOtherEntities(p, box, e -> e instanceof LivingEntity && !(e instanceof HostileEntity && ((HostileEntity)e).isAttacking()));

            if (!entities.isEmpty()) {
                Entity target = entities.get(sw.getRandom().nextInt(entities.size()));
                p.startRiding(target, true);
                LOG.info("[KostiqAI] {} is now riding a {}", name, target.getType().getName().getString());
            } else {
                LOG.info("[KostiqAI] force ride for {} failed: no suitable entities nearby", name);
            }
        });
    }

    // ===== WORLD / CONTEXT HELPERS =====
    private Optional<BlockPos> findSafeWitherSpawnLocation(ServerWorld world, BlockPos center) {
        for (int yOff = 5; yOff >= -5; yOff--) {
            BlockPos currentPos = center.up(yOff);
            // Check 3x3 base
            boolean baseSolid = true;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!world.getBlockState(currentPos.add(dx, -1, dz)).isSolidBlock(world, currentPos.add(dx, -1, dz))) {
                        baseSolid = false;
                        break;
                    }
                }
                if (!baseSolid) break;
            }
            if (!baseSolid) continue;

            // Check 3x4x3 volume for air/passable blocks
            boolean volumeClear = true;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy < 4; dy++) {
                        BlockState state = world.getBlockState(currentPos.add(dx, dy, dz));
                        if (state.blocksMovement() || state.isOf(Blocks.NETHER_PORTAL)) {
                            volumeClear = false;
                            break;
                        }
                    }
                    if (!volumeClear) break;
                }
                if (!volumeClear) break;
            }

            if (volumeClear) {
                return Optional.of(currentPos);
            }
        }
        return Optional.empty();
    }

    private static RegistryKey<World> worldKeyOf(Identifier worldId) {
        if (worldId.equals(World.OVERWORLD.getValue())) return World.OVERWORLD;
        if (worldId.equals(World.NETHER.getValue()))    return World.NETHER;
        if (worldId.equals(World.END.getValue()))       return World.END;
        return RegistryKey.of(RegistryKeys.WORLD, worldId);
    }
    private static String biomeId(ServerWorld sw, BlockPos pos) {
        try { return sw.getBiome(pos).getKey().map(k -> k.getValue().toString()).orElse("unknown"); }
        catch (Exception ignore) { return "unknown"; }
    }
    private static int lightAt(ServerWorld sw, BlockPos pos) {
        try { return Math.max(sw.getLightLevel(pos), sw.getLightLevel(pos.up())); }
        catch (Exception ignore) { return 0; }
    }
    private static String blockIdAt(ServerWorld sw, BlockPos pos) {
        try { return Registries.BLOCK.getId(sw.getBlockState(pos).getBlock()).toString(); }
        catch (Exception ignore) { return "minecraft:air"; }
    }
    private static boolean hasElytra(ServerPlayerEntity p) {
        ItemStack c = p.getEquippedStack(EquipmentSlot.CHEST);
        return !c.isEmpty() && Registries.ITEM.getId(c.getItem()).toString().contains("elytra");
    }
    private static int armorTier(ServerPlayerEntity p) {
        int t = 0;
        for (EquipmentSlot s : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack it = p.getEquippedStack(s); if (it.isEmpty()) continue;
            String id = Registries.ITEM.getId(it.getItem()).toString();
            if (id.contains("leather")) t += 1; else if (id.contains("chain")) t += 2; else if (id.contains("iron")) t += 3;
            else if (id.contains("gold")) t += 2; else if (id.contains("diamond")) t += 4; else if (id.contains("netherite")) t += 5;
        }
        return t;
    }
    private static boolean isPickaxe(Item i) { return idHas(i,"_pickaxe"); }
    private static boolean isTool(Item i) {
        return isPickaxe(i) || idHas(i,"_axe") || idHas(i,"_shovel") || idHas(i,"_hoe") || i == Items.SHEARS;
    }
    private static boolean idHas(Item i, String needle) {
        String id = Registries.ITEM.getId(i).toString();
        return id.contains(needle);
    }
    private static net.minecraft.util.hit.HitResult ray(ServerPlayerEntity p, double dist) {
        return p.raycast(dist, 0f, false);
    }
    private static boolean looksLikeMining(ServerPlayerEntity p) {
        if (p.isCreative() || p.isSpectator()) return false;
        var held = p.getMainHandStack();
        if (held.isEmpty() || !isPickaxe(held.getItem())) return false;
        var hr = ray(p, 5.0);
        if (hr == null || hr.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) return false;
        var bhr = (BlockHitResult) hr;
        var sw = (ServerWorld) p.getWorld();
        var st = sw.getBlockState(bhr.getBlockPos());
        return !st.isAir() && st.getHardness(sw, bhr.getBlockPos()) >= 0;
    }

    private JsonObject nearbySummary(ServerWorld sw, BlockPos center, int radius) {
        int hostiles = 0, passives = 0;
        Box box = new Box(center).expand(radius);
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, box, ent -> true)) {
            if (e instanceof ServerPlayerEntity) continue;
            if (e instanceof HostileEntity) hostiles++; else passives++;
        }
        JsonObject o = new JsonObject();
        o.addProperty("radius", radius);
        o.addProperty("hostiles", hostiles);
        o.addProperty("passives", passives);
        return o;
    }
    private int dangerScore(ServerPlayerEntity p, ServerWorld sw, BlockPos pos, boolean isCave, int light, int hostiles) {
        int score = 0;
        score += Math.max(0, 10 - (int)Math.ceil(p.getHealth()));
        if (isCave) score += 3;
        if (light < 7) score += 5;
        score += Math.min(10, hostiles * 2);
        if (!p.isCreative() && !p.isSpectator() && p.fallDistance > 2.5f) score += 2;
        return Math.min(20, score);
    }

    // ===== CONFIG =====
    private void loadConfig(MinecraftServer server) {
        Path p = server.getRunDirectory().resolve("config/kostiqai.json");
        try {
            Files.createDirectories(p.getParent());
            if (Files.notExists(p)) {
                Files.writeString(p, gson.toJson(cfg), java.nio.charset.StandardCharsets.UTF_8);
                LOG.info("[KostiqAI] wrote default config at {}", p);
            }
            String raw = Files.readString(p, java.nio.charset.StandardCharsets.UTF_8).trim();
            Cfg loaded = null;

            if (raw.startsWith("{")) {
                try (Reader r = Files.newBufferedReader(p)) { loaded = gson.fromJson(r, Cfg.class); } catch (Exception ignored) {}
            }
            if (loaded == null && raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                String unquoted = raw.substring(1, raw.length() - 1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t");
                try { loaded = gson.fromJson(unquoted, Cfg.class); Files.writeString(p, unquoted); LOG.warn("[KostiqAI] config was a quoted string; auto-fixed."); } catch (Exception ignored) {}
            }
            if (loaded == null) {
                try (Reader r = Files.newBufferedReader(p)) {
                    var jr = new com.google.gson.stream.JsonReader(r); jr.setLenient(true);
                    loaded = gson.fromJson(jr, Cfg.class);
                    LOG.warn("[KostiqAI] parsed config in lenient mode (check for comments/trailing commas).");
                } catch (Exception ignored) {}
            }
            if (loaded == null) throw new IllegalStateException("Could not parse config");

            cfg = loaded;
            if (cfg.bannedActions == null) cfg.bannedActions = new HashSet<>();
            ALLOW = cfg.allow != null ? cfg.allow : ALLOW;
            MAX_CMDS = cfg.maxCommandsPerCycle;
            loggingEnabled = cfg.logging;

            LOG.info("[KostiqAI] Config loaded: aiEnabled={}, dryRun={}, difficulty={}",
                    cfg.aiEnabled, cfg.dryRun, cfg.difficulty);

        } catch (Exception e) {
            LOG.error("[KostiqAI] config load failed", e);
        }
    }

    private void saveConfig(MinecraftServer server) {
        try {
            Path p = server.getRunDirectory().resolve("config/kostiqai.json");
            Files.createDirectories(p.getParent());
            Files.writeString(p, gson.toJson(cfg), java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            LOG.info("[KostiqAI] config saved to {}", p);
        } catch (Exception e) {
            LOG.warn("[KostiqAI] config save failed", e);
        }
    }
}

