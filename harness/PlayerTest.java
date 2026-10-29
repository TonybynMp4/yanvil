import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;

/** Boots the vanilla dedicated server with yAnvil, joins a mock player (the same way the game's
 *  own GameTestHelper does) and plays through pickups and anvil uses, checking repair_cost. */
public class PlayerTest {
    static MinecraftServer server;
    static ServerPlayer player;
    static int failures = 0;
    static final String NAME = "yanvil_test";
    static final BlockPos ANVIL = new BlockPos(1, 200, 0);

    public static void main(String[] args) throws Exception {
        net.minecraft.server.Main.main(new String[]{"--nogui"});
        server = findServer();
        while (!server.isReady()) Thread.sleep(200);
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            log("FAIL harness crashed: " + t);
        }
        log("done, " + failures + " failure(s)");
        Runtime.getRuntime().halt(0);
    }

    static void run() throws Exception {
        on(() -> {
            ServerLevel level = server.overworld();
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), NAME), false);
            player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            cmd("forceload add 0 0");
            cmd("fill -1 199 -1 2 199 1 minecraft:stone");
            cmd("setblock 1 200 0 minecraft:anvil");
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
        // (every item has a default repair_cost of 0, so check the value; yAnvil would set 5 + 1)
        check("enchanted book untouched", () -> rcOf(Items.ENCHANTED_BOOK), 0);

        // Outside a station, changing a priced item doesn't refire (no fix on every inventory change)
        on(() -> cmd("item modify entity " + NAME + " container.0 {type:\"minecraft:set_components\",components:{\"minecraft:repair_cost\":31}}"));
        step();
        check("stale price left alone while idle", () -> rc(0), 31);

        // Opening the anvil fixes everything before it goes in and marks the player busy
        on(() -> player.gameMode.useItemOn(player, server.overworld(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(ANVIL).add(0, 0.5, 0), Direction.UP, ANVIL, false)));
        step();
        check("anvil menu open", () -> player.containerMenu instanceof AnvilMenu ? 1 : 0, 1);
        check("opening the anvil resets the price", () -> rc(0), 5);
        check("busy flag set", () -> score("yanvil.busy"), 1);

        // Adding an enchantment while busy updates the price
        on(() -> cmd("enchant " + NAME + " minecraft:sharpness 5"));
        step();
        check("sharpness V: 5 + 1", () -> rc(0), 6);

        // Anvil: sword + Unbreaking III book. Vanilla prices the result at 2 x 6 + 1; yAnvil resets
        // it to 5 + 2 once it's back in the inventory
        anvilCombine("unbreaking", 6 + 3, 13, 7);
        // Same session, second book: Mending
        on(() -> cmd("give " + NAME + " minecraft:enchanted_book[minecraft:stored_enchantments={\"minecraft:mending\":1}]"));
        step();
        anvilCombine("mending", 7 + 2, 15, 8);

        // A netherite sword picked up mid-session is priced at netherite's base
        on(() -> cmd("give " + NAME + " minecraft:netherite_sword"));
        step();
        check("fresh netherite sword costs 8", () -> rcOf(Items.NETHERITE_SWORD), 8);

        // Busy clears on the first change that fixes nothing once the window has passed
        on(() -> {
            player.closeContainer();
            cmd("scoreboard players set " + NAME + " yanvil.until 0");
            cmd("item modify entity " + NAME + " container.0 {type:\"minecraft:set_damage\",damage:-0.1,add:true}");
        });
        step();
        check("busy flag clears when idle", () -> score("yanvil.busy"), 0);
        check("sword keeps its price", () -> rc(0), 8);
    }

    /** Shift-clicks the sword (hotbar 0) and the named book into the open anvil, checks the
     *  vanilla result price and the level cost, then shift-clicks the result back */
    static void anvilCombine(String ench, int cost, int vanillaRc, int price) throws Exception {
        on(() -> {
            AnvilMenu menu = (AnvilMenu) player.containerMenu;
            int bookSlot = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (s.is(Items.ENCHANTED_BOOK) && s.get(DataComponents.STORED_ENCHANTMENTS).toString().contains(ench)) bookSlot = i;
            }
            menu.clicked(menuIndex(0), 0, ContainerInput.QUICK_MOVE, player);
            menu.clicked(menuIndex(bookSlot), 0, ContainerInput.QUICK_MOVE, player);
        });
        step();
        AnvilMenu menu = (AnvilMenu) player.containerMenu;
        check(ench + ": anvil cost", menu::getCost, cost);
        check(ench + ": vanilla result price", () -> menu.getSlot(2).getItem().getOrDefault(DataComponents.REPAIR_COST, 0), vanillaRc);
        int levels = player.experienceLevel;
        on(() -> menu.clicked(2, 0, ContainerInput.QUICK_MOVE, player));
        step();
        check(ench + ": levels paid", () -> levels - player.experienceLevel, cost);
        check(ench + ": result repriced", () -> rcOf(Items.DIAMOND_SWORD), price);
        // Put the sword back in hotbar slot 0 for the next round
        on(() -> {
            for (int i = 0; i < 36; i++) {
                if (player.getInventory().getItem(i).is(Items.DIAMOND_SWORD) && i != 0) {
                    ItemStack s = player.getInventory().getItem(i);
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                    player.getInventory().setItem(0, s);
                }
            }
        });
        step();
    }

    /** Anvil menu index of a player inventory slot: 0-2 are the anvil, then main inventory, then hotbar */
    static int menuIndex(int inv) {
        return inv < 9 ? 30 + inv : inv - 9 + 3;
    }

    static int rc(int slot) {
        return player.getInventory().getItem(slot).getOrDefault(DataComponents.REPAIR_COST, 0);
    }

    static int rcOf(net.minecraft.world.item.Item item) {
        return book(item).getOrDefault(DataComponents.REPAIR_COST, -1);
    }

    static ItemStack book(net.minecraft.world.item.Item item) {
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(item)) return player.getInventory().getItem(i);
        return ItemStack.EMPTY;
    }

    static int score(String objective) {
        Scoreboard sb = server.getScoreboard();
        Objective o = sb.getObjective(objective);
        ReadOnlyScoreInfo info = o == null ? null : sb.getPlayerScoreInfo(player, o);
        return info == null ? 0 : info.value();
    }

    static void cmd(String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    /** Lets the server tick, then ticks the mock player (its connection isn't ticked by the
     *  server), which sends inventory changes to advancement triggers */
    static void step() throws Exception {
        Thread.sleep(150);
        on(() -> player.doTick());
        Thread.sleep(150);
    }

    static void check(String name, Supplier<Integer> actual, int expected) throws Exception {
        AtomicReference<Integer> got = new AtomicReference<>();
        on(() -> got.set(actual.get()));
        boolean ok = got.get() == expected;
        if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + name + " (expected " + expected + ", got " + got.get() + ")");
    }

    static void on(Runnable r) {
        server.executeBlocking(r);
    }

    static void log(String s) {
        System.out.println("PLAYERTEST: " + s);
    }

    static MinecraftServer findServer() throws Exception {
        while (true) {
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
                    Object v = f.get(task);
                    if (v instanceof AtomicReference<?> ref) {
                        while (ref.get() == null) Thread.sleep(50);
                        return (MinecraftServer) ref.get();
                    }
                }
            }
            Thread.sleep(100);
        }
    }
}
