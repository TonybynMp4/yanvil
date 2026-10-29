import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;

/** Boots the vanilla dedicated server with yAnvil, joins a mock player (the same way the game's
 *  own GameTestHelper does) and plays through pickups and station uses, checking repair_cost. */
public class PlayerTest {
    static MinecraftServer server;
    static ServerPlayer player;
    static int failures = 0;
    static final String NAME = "yanvil_test";
    static final BlockPos ANVIL = new BlockPos(1, 200, 0);
    static final BlockPos GRINDSTONE = new BlockPos(-1, 200, 0);
    static final BlockPos SMITHING_TABLE = new BlockPos(0, 200, 1);
    static final BlockPos ENCHANTING_TABLE = new BlockPos(0, 200, -1);
    /** How long the server gets to start, and to run a few ticks once it has */
    static final long START_TIMEOUT_MS = 300_000, TICK_TIMEOUT_MS = 30_000;

    public static void main(String[] args) throws Exception {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        net.minecraft.server.Main.main(new String[]{"--nogui"});
        server = findServer(deadline);
        while (!server.isReady()) {
            if (System.currentTimeMillis() > deadline) abort("server did not start");
            Thread.sleep(200);
        }
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            log("FAIL harness crashed: " + t);
        }
        log("done, " + failures + " failure(s)");
        Runtime.getRuntime().halt(failures == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        on(() -> {
            ServerLevel level = server.overworld();
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), NAME), false);
            player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            // Gives the connection a channel to write to, so sending packets to the player works
            new EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            cmd("forceload add 0 0");
            cmd("fill -1 199 -1 2 199 1 minecraft:stone");
            cmd("setblock " + pos(ANVIL) + " minecraft:anvil");
            cmd("setblock " + pos(GRINDSTONE) + " minecraft:grindstone[face=floor]");
            cmd("setblock " + pos(SMITHING_TABLE) + " minecraft:smithing_table");
            cmd("setblock " + pos(ENCHANTING_TABLE) + " minecraft:enchanting_table");
            cmd("tp " + NAME + " 0 200 0");
            cmd("gamemode survival " + NAME);
            cmd("xp set " + NAME + " 100 levels");
        });
        step();

        // A fresh diamond sword is priced at its tier base
        on(() -> cmd("give " + NAME + " minecraft:diamond_sword"));
        step();
        check("fresh diamond sword costs 5", () -> rc(0), 5);

        // Enchanted books are left alone
        on(() -> cmd("give " + NAME + " minecraft:enchanted_book[minecraft:stored_enchantments={\"minecraft:unbreaking\":3}]"));
        step();
        // Every item has a default repair_cost of 0, so check that the stack doesn't override it
        // (the patch lists only the stack's own components)
        check("enchanted book received", () -> find(Items.ENCHANTED_BOOK).isEmpty() ? 0 : 1, 1);
        check("enchanted book untouched",
            () -> find(Items.ENCHANTED_BOOK).getComponentsPatch().forget(t -> t != DataComponents.REPAIR_COST).isEmpty() ? 1 : 0, 1);

        // Outside a station, changing a priced item doesn't refire (no fix on every inventory change)
        setStalePrice();
        check("stale price left alone while idle", () -> rc(0), 31);

        // Opening the anvil fixes everything before it goes in and marks the player busy
        open(ANVIL, AnvilMenu.class);
        check("opening the anvil resets the price", () -> rc(0), 5);
        check("busy flag set", () -> score("yanvil.busy"), 1);

        // Adding an enchantment while busy updates the price
        on(() -> cmd("enchant " + NAME + " minecraft:sharpness 5"));
        step();
        check("sharpness V: 5 + 1", () -> rc(0), 6);

        // Anvil: sword + Unbreaking III book. Vanilla prices the result at 2 x 6 + 1; yAnvil resets
        // it to 5 + 2 once it's back in the inventory. Repricing it pushes the end of the busy
        // window to 1200 ticks from now
        on(() -> {
            cmd("execute store result score " + NAME + " yanvil.until run time query gametime");
            cmd("scoreboard players add " + NAME + " yanvil.until 100");
        });
        int until = get(() -> score("yanvil.until"));
        anvilCombine("unbreaking", 6 + 3, 13, 7);
        check("a fix extends the busy window", () -> score("yanvil.until") - until > 1000 ? 1 : 0, 1);
        // Same session, second book: Mending
        on(() -> cmd("give " + NAME + " minecraft:enchanted_book[minecraft:stored_enchantments={\"minecraft:mending\":1}]"));
        step();
        anvilCombine("mending", 7 + 2, 15, 8);

        // A netherite sword picked up mid-session is priced at netherite's base
        on(() -> cmd("give " + NAME + " minecraft:netherite_sword"));
        step();
        check("fresh netherite sword costs 8", () -> rcOf(Items.NETHERITE_SWORD), 8);

        // Grindstone: a fully cleaned item comes out with no repair_cost and gets its base price back
        on(() -> cmd("give " + NAME + " minecraft:iron_sword[minecraft:enchantments={\"minecraft:sharpness\":3}]"));
        step();
        check("enchanted iron sword: 3 + 1", () -> rcOf(Items.IRON_SWORD), 4);
        GrindstoneMenu grindstone = open(GRINDSTONE, GrindstoneMenu.class);
        quickMove(grindstone, menuIndex(slotOf(Items.IRON_SWORD), 3));
        check("grindstone result has no repair cost", () -> grindstone.getSlot(2).getItem().getOrDefault(DataComponents.REPAIR_COST, -1), 0);
        quickMove(grindstone, 2);
        check("cleaned iron sword costs 3", () -> rcOf(Items.IRON_SWORD), 3);

        // Smithing: the netherite upgrade keeps the diamond item's price, then gets netherite's
        on(() -> {
            cmd("give " + NAME + " minecraft:diamond_pickaxe");
            cmd("give " + NAME + " minecraft:netherite_upgrade_smithing_template");
            cmd("give " + NAME + " minecraft:netherite_ingot");
        });
        step();
        check("fresh diamond pickaxe costs 5", () -> rcOf(Items.DIAMOND_PICKAXE), 5);
        SmithingMenu smithing = open(SMITHING_TABLE, SmithingMenu.class);
        for (Item item : new Item[]{Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, Items.DIAMOND_PICKAXE, Items.NETHERITE_INGOT}) {
            quickMove(smithing, menuIndex(slotOf(item), 4));
        }
        check("smithing result keeps the diamond price", () -> smithing.getSlot(3).getItem().getOrDefault(DataComponents.REPAIR_COST, -1), 5);
        quickMove(smithing, 3);
        check("netherite pickaxe costs 8", () -> rcOf(Items.NETHERITE_PICKAXE), 8);

        // Enchanting table: the enchantments it adds are counted once the item is back
        on(() -> {
            cmd("give " + NAME + " minecraft:iron_axe");
            cmd("give " + NAME + " minecraft:lapis_lazuli 3");
        });
        step();
        check("fresh iron axe costs 3", () -> rcOf(Items.IRON_AXE), 3);
        EnchantmentMenu table = open(ENCHANTING_TABLE, EnchantmentMenu.class);
        quickMove(table, menuIndex(slotOf(Items.IRON_AXE), 2));
        quickMove(table, menuIndex(slotOf(Items.LAPIS_LAZULI), 2));
        boolean enchanted = get(() -> table.clickMenuButton(player, 0));
        check("enchanting table enchants", () -> enchanted ? 1 : 0, 1);
        step();
        quickMove(table, 0);
        check("enchanted iron axe has enchantments", () -> find(Items.IRON_AXE).getEnchantments().size() > 0 ? 1 : 0, 1);
        check("enchanted iron axe: 3 + its enchantments",
            () -> rcOf(Items.IRON_AXE) - find(Items.IRON_AXE).getEnchantments().size(), 3);

        // Busy clears on the first change that fixes nothing once the window has passed
        on(() -> {
            player.closeContainer();
            cmd("scoreboard players set " + NAME + " yanvil.until 0");
            cmd("item modify entity " + NAME + " container.0 {type:\"minecraft:set_damage\",damage:-0.1,add:true}");
        });
        step();
        check("busy flag clears when idle", () -> score("yanvil.busy"), 0);
        check("sword keeps its price", () -> rc(0), 8);

        // Reloading the pack prices what's already in the inventory
        setStalePrice();
        check("stale price left alone after the busy window", () -> rc(0), 31);
        on(() -> cmd("reload"));
        waitFor(() -> rc(0) != 31, 200);
        check("reload reprices the inventory", () -> rc(0), 8);
    }

    /** Sets the item in hotbar slot 0 to a stale vanilla price */
    static void setStalePrice() throws Exception {
        on(() -> cmd("item modify entity " + NAME + " container.0 {type:\"minecraft:set_components\",components:{\"minecraft:repair_cost\":31}}"));
        step();
    }

    /** Uses the block at pos like a right click and checks the menu it opens */
    static <M extends AbstractContainerMenu> M open(BlockPos pos, Class<M> menu) throws Exception {
        on(() -> {
            player.closeContainer();
            player.gameMode.useItemOn(player, server.overworld(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false));
        });
        step();
        boolean ok = menu.isInstance(player.containerMenu);
        check(menu.getSimpleName() + " open", () -> ok ? 1 : 0, 1);
        if (!ok) throw new IllegalStateException(menu.getSimpleName() + " did not open");
        return menu.cast(player.containerMenu);
    }

    /** Shift-clicks the sword (hotbar 0) and the named book into the open anvil, checks the
     *  vanilla result price and the level cost, then shift-clicks the result back */
    static void anvilCombine(String ench, int cost, int vanillaRc, int price) throws Exception {
        AnvilMenu menu = (AnvilMenu) player.containerMenu;
        int bookSlot = get(() -> slotOf(s -> s.is(Items.ENCHANTED_BOOK)
            && s.get(DataComponents.STORED_ENCHANTMENTS).keySet().stream().anyMatch(h -> h.getRegisteredName().equals("minecraft:" + ench))));
        quickMove(menu, menuIndex(0, 3));
        quickMove(menu, menuIndex(bookSlot, 3));
        check(ench + ": anvil cost", menu::getCost, cost);
        check(ench + ": vanilla result price", () -> menu.getSlot(2).getItem().getOrDefault(DataComponents.REPAIR_COST, 0), vanillaRc);
        int levels = get(() -> player.experienceLevel);
        quickMove(menu, 2);
        check(ench + ": levels paid", () -> levels - player.experienceLevel, cost);
        check(ench + ": result repriced", () -> rcOf(Items.DIAMOND_SWORD), price);
        // Put the sword back in hotbar slot 0 for the next round
        on(() -> {
            int i = slotOf(Items.DIAMOND_SWORD);
            if (i != 0) {
                ItemStack s = player.getInventory().getItem(i);
                player.getInventory().setItem(i, ItemStack.EMPTY);
                player.getInventory().setItem(0, s);
            }
        });
        step();
    }

    static void quickMove(AbstractContainerMenu menu, int slot) throws Exception {
        on(() -> menu.clicked(slot, 0, ContainerInput.QUICK_MOVE, player));
        step();
    }

    /** Menu index of a player inventory slot: the menu's own slots come first, then the main
     *  inventory, then the hotbar */
    static int menuIndex(int inv, int menuSlots) {
        return inv < 9 ? menuSlots + 27 + inv : inv - 9 + menuSlots;
    }

    static int rc(int slot) {
        return player.getInventory().getItem(slot).getOrDefault(DataComponents.REPAIR_COST, 0);
    }

    static int rcOf(Item item) {
        ItemStack s = find(item);
        return s.isEmpty() ? -1 : s.getOrDefault(DataComponents.REPAIR_COST, 0);
    }

    /** The first stack of item in the inventory, or an empty stack */
    static ItemStack find(Item item) {
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(item)) return player.getInventory().getItem(i);
        return ItemStack.EMPTY;
    }

    static int slotOf(Item item) {
        return slotOf(s -> s.is(item));
    }

    /** The first inventory slot whose stack matches; fails the test if there's none */
    static int slotOf(Predicate<ItemStack> match) {
        for (int i = 0; i < 36; i++) if (match.test(player.getInventory().getItem(i))) return i;
        throw new IllegalStateException("no matching item in the inventory");
    }

    static int score(String objective) {
        Scoreboard sb = server.getScoreboard();
        Objective o = sb.getObjective(objective);
        ReadOnlyScoreInfo info = o == null ? null : sb.getPlayerScoreInfo(player, o);
        return info == null ? 0 : info.value();
    }

    static String pos(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    static void cmd(String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /** Lets the server tick, then ticks the mock player (its connection isn't ticked by the
     *  server), which sends inventory changes to advancement triggers */
    static void step() throws Exception {
        waitTicks(2);
        on(() -> player.doTick());
        waitTicks(2);
    }

    static void waitTicks(int ticks) throws Exception {
        int target = get(server::getTickCount) + ticks;
        long deadline = System.currentTimeMillis() + TICK_TIMEOUT_MS;
        while (get(server::getTickCount) < target) {
            if (System.currentTimeMillis() > deadline) throw new IllegalStateException("server stopped ticking");
            Thread.sleep(10);
        }
    }

    /** Steps until condition holds (checked on the server thread), for at most maxSteps steps */
    static void waitFor(BooleanSupplier condition, int maxSteps) throws Exception {
        for (int i = 0; i < maxSteps && !get(condition::getAsBoolean); i++) step();
    }

    static void check(String name, Supplier<Integer> actual, int expected) throws Exception {
        int got = get(actual);
        boolean ok = got == expected;
        if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + name + " (expected " + expected + ", got " + got + ")");
    }

    /** Runs on the server thread and returns the result */
    static <T> T get(Supplier<T> s) {
        AtomicReference<T> got = new AtomicReference<>();
        on(() -> got.set(s.get()));
        return got.get();
    }

    static void on(Runnable r) {
        server.executeBlocking(r);
    }

    static void log(String s) {
        System.out.println("PLAYERTEST: " + s);
    }

    static void abort(String reason) {
        log("FAIL " + reason);
        Runtime.getRuntime().halt(1);
    }

    /** Main.main doesn't return the server: find it in the AtomicReference that the "Server thread"
     *  runnable (MinecraftServer.spin) captures. This reads JDK internals (Thread.holder.task, hence
     *  --add-opens java.base/java.lang), so a JDK update can break it. */
    static MinecraftServer findServer(long deadline) throws Exception {
        while (System.currentTimeMillis() < deadline) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (!t.getName().equals("Server thread")) continue;
                Field holderF = Thread.class.getDeclaredField("holder");
                holderF.setAccessible(true);
                Object holder = holderF.get(t);
                Field taskF = holder.getClass().getDeclaredField("task");
                taskF.setAccessible(true);
                Object task = taskF.get(holder);
                for (Field f : task.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.get(task) instanceof AtomicReference<?> ref) {
                        while (ref.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
                        if (ref.get() != null) return (MinecraftServer) ref.get();
                    }
                }
            }
            Thread.sleep(100);
        }
        abort("server thread not found");
        return null;
    }
}
