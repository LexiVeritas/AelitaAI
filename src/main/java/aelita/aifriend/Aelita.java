package aelita.aifriend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.item.ToolItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.entity.LivingEntity;


public class Aelita implements ModInitializer {

    public static final String MOD_ID = "aelita";

    public static final EntityType<AelitaEntity> AELITA_ENTITY = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "aelita"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, AelitaEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 1.8f))
                    .build()
    );

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(AELITA_ENTITY, AelitaEntity.createAttributes());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            ServerWorld world = player.getServerWorld();

            for (AelitaEntity oldAelita : world.getEntitiesByClass(AelitaEntity.class, new Box(-30000, -64, -30000, 30000, 320, 30000), e -> true)) {
                oldAelita.discard();
            }

            Vec3d spawnPos = player.getPos().add(player.getRotationVector().multiply(2.0));
            AelitaEntity aelita = AELITA_ENTITY.create(world);
            if (aelita != null) {
                aelita.refreshPositionAndAngles(spawnPos.x, player.getY(), spawnPos.z, player.getYaw() + 180, 0);
                world.spawnEntity(aelita);
                aelita.greetPlayer(player);
            }
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String userText = message.getContent().getString();
            ServerWorld world = sender.getServerWorld();

            for (AelitaEntity aelita : world.getEntitiesByClass(AelitaEntity.class, new Box(-30000, -64, -30000, 30000, 320, 30000), e -> true)) {
                aelita.handleChatDialogue(sender, userText);
            }
        });
    }

    // =========================================================================
    // КЛАСС СУЩНОСТИ AELITA
    // =========================================================================
    public static class AelitaEntity extends PathAwareEntity {

        public enum TaskState {
            IDLE, MINE_BLOCK, COLLECT_DROP, DELIVER_TO_PLAYER, DELIVER_TO_CHEST,
            PROTECTING_PLAYER, DEAD_SPECTATOR, SITTING, STANDING_STILL, FREE_ROAM,
            EXPANDED_SEARCH, TAKE_FROM_CHEST
        }

        private boolean isThinking = false;
        private PlayerEntity targetPlayerToFollow = null;
        private TaskState currentTask = TaskState.IDLE;
        private String currentActionDescription = "Отдыхаю";

        private BlockPos targetBlockPos = null;
        private int workTicks = 0;
        private int progressCurrent = 0;
        private int progressMax = 0;
        private int targetMiningAmount = 1;
        private int minedCount = 0;
        private String currentTargetResource = "";

        private long guardDisabledUntilTick = 0;
        private long lastPlayerCommandTick = 0;
        private long lastChatHelpCryTick = 0;
        private boolean isGuardModeActive = false;

        private final List<String> memoryHistory = new ArrayList<>();
        private static final int MAX_MEMORY_SIZE = 10;
        private final DefaultedList<ItemStack> npcInventory = DefaultedList.ofSize(27, ItemStack.EMPTY);
        private final Random random = new Random();

        private void sayInChat(String text) {
            if (this.getServer() != null) {
                new java.util.Timer().schedule(new java.util.TimerTask() {
                    @Override
                    public void run() {
                        if (AelitaEntity.this.getServer() != null) {
                            AelitaEntity.this.getServer().getPlayerManager().broadcast(Text.literal("§d[Aelita]: §f" + text), false);
                        }
                    }
                }, 100);
            }
        }

        private String normalizeText(String input) {
            if (input == null) return "";
            return input.toLowerCase().replace('ё', 'е').trim();
        }

        private BlockPos findAnyResourceBlock(String keyword, int maxRadius) {
            BlockPos npcPos = this.getBlockPos();
            World world = this.getWorld();
            String cleanKey = normalizeText(keyword);

            String targetKey = cleanKey;
            if (cleanKey.contains("берез")) targetKey = "birch";
            else if (cleanKey.contains("дуб")) targetKey = "oak";
            else if (cleanKey.contains("сосн") || cleanKey.contains("ель")) targetKey = "spruce";
            else if (cleanKey.contains("песок")) targetKey = "sand";
            else if (cleanKey.contains("алмаз")) targetKey = "diamond";
            else if (cleanKey.contains("желез")) targetKey = "iron";

            for (int r = 0; r <= maxRadius; r++) {
                for (int x = -r; x <= r; x++) {
                    for (int z = -r; z <= r; z++) {
                        if (Math.abs(x) == r || Math.abs(z) == r) {
                            for (int y = -3; y <= 3; y++) {
                                BlockPos checkPos = npcPos.add(x, y, z);
                                if (!World.isValid(checkPos)) continue;

                                Block block = world.getBlockState(checkPos).getBlock();
                                Identifier id = Registries.BLOCK.getId(block);
                                String path = id.getPath().toLowerCase();
                                String localizedName = normalizeText(block.getName().getString());

                                if (path.contains(targetKey) || localizedName.contains(cleanKey) || path.contains(cleanKey)) {
                                    return checkPos;
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }

        private BlockPos findChestContainingItem(String keyword, int maxRadius) {
            BlockPos npcPos = this.getBlockPos();
            World world = this.getWorld();
            String cleanKey = normalizeText(keyword);

            for (int r = 0; r <= maxRadius; r += 16) {
                for (int x = -r; x <= r; x += 16) {
                    for (int z = -r; z <= r; z += 16) {
                        for (int y = -15; y <= 15; y++) {
                            BlockPos checkPos = npcPos.add(x, y, z);
                            BlockEntity be = world.getBlockEntity(checkPos);
                            if (be instanceof ChestBlockEntity chest) {
                                for (int i = 0; i < chest.size(); i++) {
                                    ItemStack stack = chest.getStack(i);
                                    if (!stack.isEmpty()) {
                                        String itemName = normalizeText(stack.getName().getString());
                                        String itemId = Registries.ITEM.getId(stack.getItem()).getPath().toLowerCase();
                                        if (itemName.contains(cleanKey) || itemId.contains(cleanKey)) {
                                            return checkPos;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }

        public AelitaEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
            super(entityType, world);
            this.setCustomName(Text.literal("Aelita"));
            this.setCustomNameVisible(true);
        }

        @Override
        protected void initGoals() {
            this.goalSelector.add(0, new SwimGoal(this));
            this.goalSelector.add(1, new MeleeAttackGoal(this, 1.25D, true));
            this.goalSelector.add(2, new WanderAroundFarGoal(this, 1.0D));
            this.goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));

            this.targetSelector.add(1, new RevengeGoal(this));
        }

        public static DefaultAttributeContainer.Builder createAttributes() {
            return MobEntity.createMobAttributes()
                    .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                    .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3f)
                    .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.0);
        }

        public void greetPlayer(PlayerEntity player) {
            this.targetPlayerToFollow = player;
            this.lastPlayerCommandTick = this.getWorld().getTime();

            if (this.getServer() != null) {
                this.getServer().execute(() -> {
                    this.getWorld().getServer().submit(() -> {
                        try {
                            Thread.sleep(800);
                        } catch (InterruptedException ignored) {
                        }
                        if (this.getServer() != null) {
                            this.sayInChat("О, " + player.getName().getString() + ", ты здесь. Наконец-то зашел!");
                        }
                    });
                });
            }
        }

        @Override
        public void tick() {
            super.tick();

            if (this.getWorld().isClient()) return;
            long currentTick = this.getWorld().getTime();

            // Автоподбор вокруг (без сброса навигации при копании)
            collectNearbyItemsAutomatically();

            if (currentTask != TaskState.FREE_ROAM && currentTask != TaskState.SITTING && currentTask != TaskState.STANDING_STILL
                    && currentTask != TaskState.DEAD_SPECTATOR
                    && (currentTick - lastPlayerCommandTick > 12000)) {
                startFreeRoam("Прошло 10 минут без команд, пойду погуляю рядом.");
            }

            if (currentTask == TaskState.DEAD_SPECTATOR) {
                if (targetPlayerToFollow != null) {
                    this.teleport(targetPlayerToFollow.getX(), targetPlayerToFollow.getY() + 1.5, targetPlayerToFollow.getZ(), false);
                }
                return;
            }

            if (currentTask == TaskState.SITTING || currentTask == TaskState.STANDING_STILL) {
                this.getNavigation().stop();
                return;
            }

            if (currentTask == TaskState.FREE_ROAM && targetPlayerToFollow != null) {
                if (this.squaredDistanceTo(targetPlayerToFollow) > 400.0) {
                    this.getNavigation().startMovingTo(targetPlayerToFollow, 1.1D);
                }
            }

            if (currentTask == TaskState.EXPANDED_SEARCH) {
                this.currentActionDescription = "Сканирую территорию (16 чанков)...";

                BlockPos foundPos = findAnyResourceBlock(currentTargetResource, 256);
                if (foundPos != null) {
                    this.targetBlockPos = foundPos;
                    this.currentTask = TaskState.MINE_BLOCK;
                    this.sayInChat("Нашла «" + currentTargetResource + "» в радиусе 16 чанков! Иду добывать.");
                } else {
                    BlockPos chestPos = findChestContainingItem(currentTargetResource, 256);
                    if (chestPos != null) {
                        this.targetBlockPos = chestPos;
                        this.currentTask = TaskState.TAKE_FROM_CHEST;
                        this.sayInChat("Нашла «" + currentTargetResource + "» в сундуке на координатах " + chestPos.toShortString() + "! Иду забирать.");
                    } else {
                        this.sayInChat("Обошла 16 чанков вокруг, но ресурс «" + currentTargetResource + "» ни в блоках, ни в сундуках не нашла.");
                        this.currentTask = TaskState.IDLE;
                        this.currentActionDescription = "Отдыхаю";
                    }
                }
                return;
            }

            if (targetPlayerToFollow != null && currentTick > guardDisabledUntilTick) {
                checkPlayerVulnerability(targetPlayerToFollow);
            }

            if (isGuardModeActive && targetPlayerToFollow != null) {
                handleGuardLogic();
            }

            if (currentTask == TaskState.FREE_ROAM) {
                tickFreeRoam();
            }

            if (currentTask == TaskState.IDLE && targetPlayerToFollow != null) {
                if (this.squaredDistanceTo(targetPlayerToFollow) > 4.0) {
                    this.getNavigation().startMovingTo(targetPlayerToFollow, 1.2D);
                }
            }

            if (currentTask == TaskState.TAKE_FROM_CHEST && targetBlockPos != null) {
                double dist = this.squaredDistanceTo(targetBlockPos.getX() + 0.5, targetBlockPos.getY(), targetBlockPos.getZ() + 0.5);
                if (dist > 4.0) {
                    this.getNavigation().startMovingTo(targetBlockPos.getX(), targetBlockPos.getY(), targetBlockPos.getZ(), 1.2D);
                } else {
                    this.getNavigation().stop();
                    BlockEntity be = this.getWorld().getBlockEntity(targetBlockPos);
                    if (be instanceof ChestBlockEntity chest) {
                        for (int i = 0; i < chest.size(); i++) {
                            ItemStack stack = chest.getStack(i);
                            if (!stack.isEmpty()) {
                                String itemName = normalizeText(stack.getName().getString());
                                String itemId = Registries.ITEM.getId(stack.getItem()).getPath().toLowerCase();
                                if (itemName.contains(normalizeText(currentTargetResource)) || itemId.contains(normalizeText(currentTargetResource))) {
                                    ItemStack taken = stack.split(targetMiningAmount);
                                    addToNpcInventory(taken);
                                    chest.markDirty();
                                    this.sayInChat("Забрала из сундука: " + taken.getCount() + " шт. " + taken.getName().getString() + "!");
                                    break;
                                }
                            }
                        }
                    }
                    currentTask = TaskState.COLLECT_DROP;
                }
            }

            // Прямая, гарантированная добыча блоков
            if (currentTask == TaskState.MINE_BLOCK && targetBlockPos != null) {
                if (this.getWorld().isAir(targetBlockPos)) {
                    BlockPos nextPos = findAnyResourceBlock(currentTargetResource, 16);
                    if (nextPos != null) {
                        targetBlockPos = nextPos;
                    } else {
                        currentTask = TaskState.COLLECT_DROP;
                    }
                    return;
                }

                double dist = this.squaredDistanceTo(targetBlockPos.getX() + 0.5, targetBlockPos.getY(), targetBlockPos.getZ() + 0.5);

                // Если мы подошли близко (до 5 блоков) — ломаем
                if (dist <= 16.0) {
                    this.getNavigation().stop();
                    workTicks++;
                    if (workTicks >= 15) { // 0.75 секунды на блок
                        workTicks = 0;
                        this.getWorld().breakBlock(targetBlockPos, true, this);
                        minedCount++;
                        this.currentActionDescription = "Добываю " + currentTargetResource + " (" + minedCount + "/" + targetMiningAmount + ")";

                        if (minedCount < targetMiningAmount) {
                            BlockPos nextPos = findAnyResourceBlock(currentTargetResource, 16);
                            if (nextPos != null) {
                                targetBlockPos = nextPos;
                            } else {
                                this.sayInChat("Добыла " + minedCount + " шт., больше поблизости нет.");
                                currentTask = TaskState.COLLECT_DROP;
                            }
                        } else {
                            this.sayInChat("Готово! Добыла " + minedCount + " шт. " + currentTargetResource + ".");
                            currentTask = TaskState.COLLECT_DROP;
                        }
                    }
                } else {
                    this.getNavigation().startMovingTo(targetBlockPos.getX() + 0.5, targetBlockPos.getY(), targetBlockPos.getZ() + 0.5, 1.25D);
                }
            }

            if (currentTask == TaskState.COLLECT_DROP) {
                List<ItemEntity> items = this.getWorld().getEntitiesByClass(ItemEntity.class, this.getBoundingBox().expand(4.0), e -> true);
                for (ItemEntity item : items) {
                    addToNpcInventory(item.getStack().copy());
                    item.discard();
                }

                BlockPos chestPos = findNearbyChest();
                if (chestPos != null) {
                    targetBlockPos = chestPos;
                    currentTask = TaskState.DELIVER_TO_CHEST;
                } else if (targetPlayerToFollow != null) {
                    currentTask = TaskState.DELIVER_TO_PLAYER;
                } else {
                    currentTask = TaskState.IDLE;
                }
            }

            if (currentTask == TaskState.DELIVER_TO_CHEST && targetBlockPos != null) {
                double dist = this.squaredDistanceTo(targetBlockPos.getX() + 0.5, targetBlockPos.getY(), targetBlockPos.getZ() + 0.5);
                if (dist > 4.0) {
                    this.getNavigation().startMovingTo(targetBlockPos.getX(), targetBlockPos.getY(), targetBlockPos.getZ(), 1.2D);
                } else {
                    storeInventoryToChest(targetBlockPos);
                    this.sayInChat("Положила на склад!");
                    currentTask = TaskState.IDLE;
                }
            }

            if (currentTask == TaskState.DELIVER_TO_PLAYER && targetPlayerToFollow != null) {
                double dist = this.squaredDistanceTo(targetPlayerToFollow);
                if (dist > 4.0) {
                    this.getNavigation().startMovingTo(targetPlayerToFollow, 1.2D);
                } else {
                    dropInventoryToPlayer();
                    this.sayInChat("Держи, принесла тебе!");
                    currentTask = TaskState.IDLE;
                }
            }
        }

        private void collectNearbyItemsAutomatically() {
            List<ItemEntity> nearbyItems = this.getWorld().getEntitiesByClass(ItemEntity.class, this.getBoundingBox().expand(4.0), e -> true);
            for (ItemEntity itemEntity : nearbyItems) {
                ItemStack stack = itemEntity.getStack();
                if (!stack.isEmpty()) {
                    if (tryEquipBestItem(stack)) {
                        itemEntity.discard();
                    } else {
                        addToNpcInventory(stack.copy());
                        this.sayInChat("Подобрала и убрала в сумку: " + stack.getName().getString());
                        itemEntity.discard();
                    }
                }
            }
        }

        private int getItemValueScore(ItemStack stack) {
            if (stack.isEmpty()) return 0;
            String name = Registries.ITEM.getId(stack.getItem()).getPath().toLowerCase();

            int materialMultiplier = 1;
            if (name.contains("mace")) materialMultiplier = 150; // Булава имеет высокий приоритет
            else if (name.contains("netherite")) materialMultiplier = 100;
            else if (name.contains("diamond")) materialMultiplier = 80;
            else if (name.contains("iron")) materialMultiplier = 50;
            else if (name.contains("golden") || name.contains("gold")) materialMultiplier = 30;
            else if (name.contains("chainmail")) materialMultiplier = 25;
            else if (name.contains("stone")) materialMultiplier = 15;
            else if (name.contains("wooden") || name.contains("wood") || name.contains("leather")) materialMultiplier = 5;

            int baseValue = 1;
            if (stack.getItem() instanceof ArmorItem armor) {
                baseValue += armor.getProtection();
            }

            return baseValue * materialMultiplier;
        }

        private boolean tryEquipBestItem(ItemStack newStack) {
            Item item = newStack.getItem();

            if (item instanceof ArmorItem armorItem) {
                EquipmentSlot slot = armorItem.getSlotType();
                ItemStack currentArmor = this.getEquippedStack(slot);

                if (getItemValueScore(newStack) > getItemValueScore(currentArmor)) {
                    this.equipStack(slot, newStack.copy());
                    this.setEquipmentDropChance(slot, 1.0f);
                    this.sayInChat("Надела броню: " + newStack.getName().getString());
                    return true;
                }
            }

            String idPath = Registries.ITEM.getId(item).getPath().toLowerCase();
            boolean isWeapon = item instanceof SwordItem || item instanceof ToolItem || idPath.contains("mace") || idPath.contains("spear") || idPath.contains("trident");

            if (isWeapon) {
                ItemStack currentMainHand = this.getMainHandStack();
                if (getItemValueScore(newStack) > getItemValueScore(currentMainHand)) {
                    this.equipStack(EquipmentSlot.MAINHAND, newStack.copy());
                    this.setEquipmentDropChance(EquipmentSlot.MAINHAND, 1.0f);
                    this.sayInChat("Экипировала оружие: " + newStack.getName().getString());
                    return true;
                }
            }

            return false;
        }

        private void checkPlayerVulnerability(PlayerEntity player) {
            if (player.isCreative() || player.isSpectator()) return;

            boolean lowHealth = player.getHealth() < (player.getMaxHealth() / 2.0f);
            boolean armorBroken = isArmorPieceBroken(player);

            if (lowHealth && armorBroken && !isGuardModeActive && currentTask != TaskState.DEAD_SPECTATOR) {
                isGuardModeActive = true;
                currentTask = TaskState.PROTECTING_PLAYER;
                currentActionDescription = "Защищаю " + player.getName().getString();
                this.sayInChat(player.getName().getString() + " в опасности! Перехожу в режим защиты!");
            }
        }

        private boolean isArmorPieceBroken(PlayerEntity player) {
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HEAD}) {
                ItemStack stack = player.getEquippedStack(slot);
                if (!stack.isEmpty() && stack.isDamaged() && (stack.getMaxDamage() - stack.getDamage() <= 5)) {
                    return true;
                }
            }
            return false;
        }

        private void handleGuardLogic() {
            List<HostileEntity> enemies = this.getWorld().getEntitiesByClass(HostileEntity.class, this.getBoundingBox().expand(16.0), enemy -> {
                if (enemy.hasCustomName() || enemy.hasVehicle() || !this.canSee(enemy)) return false;
                return enemy.getTarget() == targetPlayerToFollow || enemy.getTarget() == this;
            });

            if (!enemies.isEmpty()) {
                this.setTarget(enemies.get(0));
            } else {
                this.setTarget(null);
            }
        }

        @Override
        public boolean damage(DamageSource source, float amount) {
            if (currentTask == TaskState.DEAD_SPECTATOR) return false;

            if (this.getHealth() - amount <= 0.0f && !this.getWorld().isClient()) {
                handleDeath(source);
                return false;
            }

            boolean result = super.damage(source, amount);
            if (result && !this.getWorld().isClient()) {
                if (source.getAttacker() instanceof HostileEntity hostile) {
                    if (!hasWeaponInInventory()) {
                        long currentTick = this.getWorld().getTime();
                        if (currentTick - lastChatHelpCryTick > 300) {
                            lastChatHelpCryTick = currentTick;
                            this.sayInChat("У меня нет оружия! Помоги с " + hostile.getName().getString() + "!");
                        }
                        if (targetPlayerToFollow != null) {
                            this.getNavigation().startMovingTo(targetPlayerToFollow, 1.4D);
                        }
                    } else {
                        this.setTarget(hostile);
                    }
                }
            }
            return result;
        }

        private void handleDeath(DamageSource source) {
            String deathReason = source.getDeathMessage(this).getString();
            if (this.getServer() != null) {
                this.getServer().getPlayerManager().broadcast(Text.literal("§c☠ [Aelita] " + deathReason), false);
            }

            if (!this.getWorld().getGameRules().getBoolean(GameRules.KEEP_INVENTORY)) {
                dropInventoryToGround();
            }

            this.setHealth(20.0f);
            this.setInvisible(true);
            this.setNoGravity(true);
            this.noClip = true;
            this.getNavigation().stop();
            this.setTarget(null);
            this.currentTask = TaskState.DEAD_SPECTATOR;
            this.currentActionDescription = "В форме духа";
            this.sayInChat("Меня вынесли... Можно мне возродиться?");
        }

        private void startFreeRoam(String announceMessage) {
            this.currentTask = TaskState.FREE_ROAM;
            this.isGuardModeActive = false;

            progressCurrent = 0;
            progressMax = 5 + random.nextInt(10);
            currentActionDescription = "Гуляю рядом";
            this.sayInChat(announceMessage);
        }

        private void tickFreeRoam() {
            if (this.age % 1200 == 0) {
                progressCurrent++;
                if (progressCurrent >= progressMax) {
                    currentTask = TaskState.IDLE;
                    currentActionDescription = "Отдыхаю";
                }
            }
        }

        private void sitOnLookedBlock(PlayerEntity player) {
            HitResult hit = player.raycast(10.0, 0.0f, false);
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHit = (BlockHitResult) hit;
                BlockPos pos = blockHit.getBlockPos().up();
                this.teleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, true);
                this.getNavigation().stop();
                this.currentTask = TaskState.SITTING;
                this.currentActionDescription = "Сижу на блоке";
                this.sayInChat("Села на блок.");
            }
        }

        private boolean hasWeaponInInventory() {
            if (!this.getMainHandStack().isEmpty()) return true;
            for (ItemStack stack : npcInventory) {
                if (stack.getItem() instanceof SwordItem || stack.getItem() instanceof ToolItem) return true;
            }
            return false;
        }

        private BlockPos findNearbyChest() {
            BlockPos npcPos = this.getBlockPos();
            World world = this.getWorld();
            for (int x = -8; x <= 8; x++) {
                for (int y = -3; y <= 3; y++) {
                    for (int z = -8; z <= 8; z++) {
                        BlockPos checkPos = npcPos.add(x, y, z);
                        if (world.getBlockEntity(checkPos) instanceof ChestBlockEntity) return checkPos;
                    }
                }
            }
            return null;
        }

        private void storeInventoryToChest(BlockPos chestPos) {
            BlockEntity be = this.getWorld().getBlockEntity(chestPos);
            if (be instanceof ChestBlockEntity chest) {
                for (int i = 0; i < npcInventory.size(); i++) {
                    ItemStack stack = npcInventory.get(i);
                    if (!stack.isEmpty()) {
                        for (int c = 0; c < chest.size(); c++) {
                            if (chest.getStack(c).isEmpty()) {
                                chest.setStack(c, stack.copy());
                                npcInventory.set(i, ItemStack.EMPTY);
                                chest.markDirty();
                                break;
                            }
                        }
                    }
                }
            }
        }

        private void dropSpecificRequestedItem(String lowerText, int requestedAmount) {
            String targetKey = lowerText.replace("отдай", "").replace("мне", "").replace("принеси", "").replace("дай", "").replaceAll("\\d+", "").trim();

            // 1. Проверяем экипированные вещи
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack equipped = this.getEquippedStack(slot);
                if (!equipped.isEmpty() && (normalizeText(equipped.getName().getString()).contains(targetKey) || targetKey.isEmpty())) {
                    senderGiveStack(targetPlayerToFollow, equipped.copy());
                    this.sayInChat("Отдала тебе из экипировки: " + equipped.getName().getString());
                    this.equipStack(slot, ItemStack.EMPTY);
                    return;
                }
            }

            // 2. Проверяем инвентарь по имени или категории
            for (int i = 0; i < npcInventory.size(); i++) {
                ItemStack stack = npcInventory.get(i);
                if (!stack.isEmpty()) {
                    String itemName = normalizeText(stack.getName().getString());
                    if (itemName.contains(targetKey) || targetKey.isEmpty()) {
                        ItemStack toGive = stack.copy();
                        toGive.setCount(Math.min(stack.getCount(), requestedAmount));

                        if (stack.getCount() <= requestedAmount) {
                            npcInventory.set(i, ItemStack.EMPTY);
                        } else {
                            stack.decrement(requestedAmount);
                        }

                        senderGiveStack(targetPlayerToFollow, toGive);
                        this.sayInChat("Держи " + toGive.getCount() + " шт. (" + toGive.getName().getString() + ")!");
                        return;
                    }
                }
            }

            this.sayInChat("У меня нет предмета «" + (targetKey.isEmpty() ? "в сумке" : targetKey) + "».");
        }

        private void senderGiveStack(PlayerEntity player, ItemStack stack) {
            ItemEntity itemEntity = new ItemEntity(this.getWorld(), player.getX(), player.getY() + 0.5, player.getZ(), stack);
            this.getWorld().spawnEntity(itemEntity);
        }

        private void dropInventoryToPlayer() {
            boolean dropped = false;
            for (int i = 0; i < npcInventory.size(); i++) {
                ItemStack stack = npcInventory.get(i);
                if (!stack.isEmpty()) {
                    senderGiveStack(targetPlayerToFollow, stack.copy());
                    npcInventory.set(i, ItemStack.EMPTY);
                    dropped = true;
                }
            }

            if (!dropped) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack equipped = this.getEquippedStack(slot);
                    if (!equipped.isEmpty()) {
                        senderGiveStack(targetPlayerToFollow, equipped.copy());
                        this.equipStack(slot, ItemStack.EMPTY);
                    }
                }
            }
        }

        private void dropInventoryToGround() {
            for (int i = 0; i < npcInventory.size(); i++) {
                ItemStack stack = npcInventory.get(i);
                if (!stack.isEmpty()) {
                    senderGiveStack(targetPlayerToFollow, stack.copy());
                    npcInventory.set(i, ItemStack.EMPTY);
                }
            }
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack equipped = this.getEquippedStack(slot);
                if (!equipped.isEmpty()) {
                    senderGiveStack(targetPlayerToFollow, equipped.copy());
                    this.equipStack(slot, ItemStack.EMPTY);
                }
            }
        }

        private void addToNpcInventory(ItemStack stack) {
            for (int i = 0; i < npcInventory.size(); i++) {
                if (npcInventory.get(i).isEmpty()) {
                    npcInventory.set(i, stack);
                    return;
                }
            }
        }

        private void addMemory(String entry) {
            memoryHistory.add(entry);
            if (memoryHistory.size() > MAX_MEMORY_SIZE) {
                memoryHistory.remove(0);
            }
        }

        public void handleChatDialogue(PlayerEntity sender, String text) {
            if (isThinking || this.getWorld().isClient()) return;

            String lowerText = normalizeText(text);
            this.targetPlayerToFollow = sender;
            this.lastPlayerCommandTick = this.getWorld().getTime();

            if (lowerText.contains("где ты") || lowerText.contains("ты где") || lowerText.contains("координаты") || lowerText.contains("где находишься")) {
                BlockPos pos = this.getBlockPos();
                this.sayInChat("Я нахожусь на координатах: X: " + pos.getX() + ", Y: " + pos.getY() + ", Z: " + pos.getZ() + " (" + currentActionDescription + ")");
                return;
            }

            if (lowerText.contains("тп") || lowerText.contains("телепорт") || lowerText.contains("к мне") || lowerText.contains("сюда") || lowerText.contains("подойди ко мне")) {
                this.sayInChat("Приготовься, перемещаюсь к тебе!");
                this.setPosition(sender.getX(), sender.getY(), sender.getZ());
                this.getNavigation().stop();
                this.currentTask = TaskState.IDLE;
                this.currentActionDescription = "Ожидание команд";
                return;
            }

            if (lowerText.contains("убей") || lowerText.contains("атакуй") || lowerText.contains("бей")) {
                this.sayInChat("Поняла, иду в атаку!");
                LivingEntity targetMob = this.getWorld().getClosestEntity(
                        LivingEntity.class,
                        TargetPredicate.DEFAULT,
                        this,
                        this.getX(), this.getY(), this.getZ(),
                        this.getBoundingBox().expand(10.0)
                );
                if (targetMob != null && targetMob != sender) {
                    this.setTarget(targetMob);
                    this.currentTask = TaskState.PROTECTING_PLAYER;
                    this.currentActionDescription = "Атакую цель";
                } else {
                    this.sayInChat("Поблизости никого нет для атаки.");
                }
                return;
            }

            if (lowerText.contains("что ты делаешь") || lowerText.contains("чем занята")) {
                this.sayInChat("Сейчас я: " + currentActionDescription);
                return;
            }

            if (lowerText.contains("сядь") || lowerText.contains("присядь")) {
                sitOnLookedBlock(sender);
                return;
            }

            if (lowerText.contains("стой") || lowerText.contains("замри") || lowerText.equals("стоп") || lowerText.equals("отмена") || lowerText.equals("хватит")) {
                String previousTaskStr = currentActionDescription;
                this.currentTask = TaskState.STANDING_STILL;
                this.targetBlockPos = null;
                this.currentActionDescription = "Стою на месте";
                this.getNavigation().stop();
                this.setTarget(null);
                this.sayInChat("Остановила задачу («" + previousTaskStr + "»). Стою на месте и жду указаний.");
                return;
            }

            // Точный перехват возврата/выдачи предметов по категориям и именам
            if (lowerText.contains("принеси") || lowerText.contains("отдай") || lowerText.contains("поделись") || lowerText.contains("дай")) {
                int amount = 1;
                Matcher matcher = Pattern.compile("\\d+").matcher(lowerText);
                if (matcher.find()) {
                    try {
                        amount = Integer.parseInt(matcher.group());
                    } catch (NumberFormatException ignored) {}
                }

                dropSpecificRequestedItem(lowerText, amount);
                return;
            }

            if (lowerText.contains("займись") || lowerText.contains("не мешай") || lowerText.contains("погуляй")) {
                startFreeRoam("Поняла, пошла прогуляюсь рядом.");
                return;
            }

            if (lowerText.contains("защищай меня") || lowerText.contains("защита")) {
                this.isGuardModeActive = true;
                this.guardDisabledUntilTick = 0;
                this.currentTask = TaskState.PROTECTING_PLAYER;
                this.currentActionDescription = "Защищаю " + sender.getName().getString();
                this.sayInChat("Поняла! Включаю режим защиты.");
                return;
            }

            if (lowerText.contains("не защищай") || lowerText.contains("выключи защиту")) {
                String previousTaskStr = currentActionDescription;
                this.currentTask = TaskState.IDLE;
                this.isGuardModeActive = false;
                this.guardDisabledUntilTick = this.getWorld().getTime() + 12000;
                this.currentActionDescription = "Отдыхаю";
                this.getNavigation().stop();
                this.setTarget(null);
                this.sayInChat("Перестала " + previousTaskStr + ". Режим защиты отключен.");
                return;
            }

            boolean isMiningRequest = lowerText.contains("добудь") || lowerText.contains("накопай") || lowerText.contains("найди") || lowerText.contains("собери");

            if (isMiningRequest) {
                int amount = 1;
                Matcher matcher = Pattern.compile("\\d+").matcher(lowerText);
                if (matcher.find()) {
                    try {
                        amount = Integer.parseInt(matcher.group());
                    } catch (NumberFormatException ignored) {}
                }

                String resourceName = lowerText
                        .replace("добудь", "")
                        .replace("накопай", "")
                        .replace("найди", "")
                        .replace("собери", "")
                        .replace("пожалуйста", "")
                        .replaceAll("\\d+", "")
                        .trim();

                if (!resourceName.isEmpty()) {
                    this.currentTargetResource = resourceName;
                    this.targetMiningAmount = amount;
                    this.minedCount = 0;

                    BlockPos foundPos = findAnyResourceBlock(resourceName, 16);
                    if (foundPos != null) {
                        this.targetBlockPos = foundPos;
                        this.currentTask = TaskState.MINE_BLOCK;
                        this.currentActionDescription = "Добываю " + resourceName + " (0/" + amount + ")";
                        this.sayInChat("Поняла, иду добывать " + resourceName + " (" + amount + " шт.)!");
                        return;
                    } else {
                        this.currentTask = TaskState.EXPANDED_SEARCH;
                        this.currentActionDescription = "Запускаю масштабный поиск (16 чанков)...";
                        this.sayInChat("Рядом «" + resourceName + "» не найден, запускаю поиск по 16 чанкам и сундукам!");
                        return;
                    }
                }
            }

            isThinking = true;
            addMemory("Игрок " + sender.getName().getString() + ": " + text);

            String systemPrompt = """
            Ты — Aelita, верный NPC-компаньон в обычном мире Minecraft.
            
            СТРОГИЕ ПРАВИЛА:
            1. ТЫ САМА ВЫПОЛНЯЕШЬ ВСЕ ДЕЙСТВИЯ! НИКОГДА не заставляй игрока копать самого.
            2. Отвечай прямолинейно, кратко (1 короткое предложение).
            """;

            String userPrompt = "Реплика игрока " + sender.getName().getString() + ": \"" + text + "\"";

            LlmApi.sendPrompt(systemPrompt, userPrompt).whenComplete((response, throwable) -> {
                if (this.getServer() != null) {
                    this.getServer().execute(() -> {
                        this.isThinking = false;

                        if (throwable != null) {
                            Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                            this.sayInChat("§c(Ошибка ИИ: " + cause.getMessage() + ")§r");
                            return;
                        }

                        if (response != null && !response.trim().isEmpty() && !response.trim().equals("...")) {
                            addMemory("Aelita: " + response.trim());
                            this.sayInChat(response.trim());
                        }
                    });
                }
            });
        }

        // =========================================================================
        // КЛИЕНТ ЛОКАЛЬНОГО API
        // =========================================================================
        public static class LlmApi {
            private static final String LM_STUDIO_URL = "http://localhost:1234/v1/chat/completions";
            private static final HttpClient CLIENT = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            public static CompletableFuture<String> sendPrompt(String systemPrompt, String userPrompt) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        JsonObject jsonBody = new JsonObject();
                        jsonBody.addProperty("model", "local-model");
                        jsonBody.addProperty("temperature", 0.3);

                        JsonArray messages = new JsonArray();
                        JsonObject sysMsg = new JsonObject();
                        sysMsg.addProperty("role", "system");
                        sysMsg.addProperty("content", systemPrompt);
                        messages.add(sysMsg);

                        JsonObject userMsg = new JsonObject();
                        userMsg.addProperty("role", "user");
                        userMsg.addProperty("content", userPrompt);
                        messages.add(userMsg);

                        jsonBody.add("messages", messages);

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(LM_STUDIO_URL))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(jsonBody.toString()))
                                .build();

                        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                        if (response.statusCode() == 200) {
                            JsonObject respJson = JsonParser.parseString(response.body()).getAsJsonObject();
                            return respJson.getAsJsonArray("choices")
                                    .get(0).getAsJsonObject()
                                    .getAsJsonObject("message")
                                    .get("content").getAsString();
                        } else {
                            throw new RuntimeException("Код " + response.statusCode());
                        }
                    } catch (java.net.ConnectException e) {
                        throw new RuntimeException("LM Studio недоступен");
                    } catch (java.net.http.HttpTimeoutException e) {
                        throw new RuntimeException("Таймаут");
                    } catch (com.google.gson.JsonParseException e) {
                        throw new RuntimeException("Ошибка JSON");
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage() != null ? e.getMessage() : "Сбой сети");
                    }
                });
            }
        }
    }
}