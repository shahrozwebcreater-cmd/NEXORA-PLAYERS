package com.nexora.players;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * First-stage independent survival controller for FightBot 6.x player entities.
 * Homes are stored per bot. The controller gathers nearby logs, crafts planks,
 * builds a small individual shelter, and continues exploring its own area.
 */
public final class SurvivalHomes {
    private final NexoraPlayers plugin;
    private BukkitTask task;
    private final Map<String, BotState> states = new HashMap<>();
    private long tick;

    private enum Phase { CHOOSE_HOME, GATHER_WOOD, BUILD_HOME, EXPLORE }
    private static final class BotState {
        Location home;
        Location target;
        Phase phase = Phase.CHOOSE_HOME;
        int idleTicks;
        boolean built;
        BotState(Location home) { this.home = home; }
    }

    public SurvivalHomes(NexoraPlayers plugin) { this.plugin = plugin; }

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("survival.enabled", true)) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 20L);
        plugin.getLogger().info("Independent survival homes controller started.");
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void tick() {
        if (++tick % 3 != 0) return;
        String worldName = plugin.getConfig().getString("gameplay.world", "world");
        for (Bot bot : findBots()) {
            Player player = bot.player;
            if (!player.getWorld().getName().equalsIgnoreCase(worldName)) continue;
            if (!player.isValid() || player.isDead()) continue;
            String key = bot.name.toLowerCase(Locale.ROOT);
            BotState state = states.computeIfAbsent(key, ignored -> loadState(bot.name, player.getLocation()));
            if (state.home == null || !state.home.getWorld().equals(player.getWorld())) {
                state.home = chooseHome(player);
                state.phase = Phase.GATHER_WOOD;
                saveState(bot.name, state);
            }
            if (state.built && state.phase == Phase.BUILD_HOME) state.phase = Phase.EXPLORE;
            switch (state.phase) {
                case CHOOSE_HOME -> {
                    state.home = chooseHome(player);
                    state.phase = Phase.GATHER_WOOD;
                    saveState(bot.name, state);
                }
                case GATHER_WOOD -> gatherWood(bot, state);
                case BUILD_HOME -> buildHome(bot, state);
                case EXPLORE -> explore(bot, state);
            }
        }
    }

    private void gatherWood(Bot bot, BotState state) {
        Player p = bot.player;
        int logs = count(p, m -> m.name().endsWith("_LOG") || m == Material.BAMBOO_BLOCK);
        int planks = count(p, m -> m.name().endsWith("_PLANKS"));
        if (planks < 24 && logs > 0) {
            for (int i = 0; i < p.getInventory().getSize(); i++) {
                ItemStack item = p.getInventory().getItem(i);
                if (item != null && (item.getType().name().endsWith("_LOG") || item.getType() == Material.BAMBOO_BLOCK)) {
                    Material plank = plankFor(item.getType());
                    if (plank != null) {
                        int amount = item.getAmount();
                        p.getInventory().setItem(i, null);
                        p.getInventory().addItem(new ItemStack(plank, Math.min(64, amount * 4)));
                    }
                }
            }
            planks = count(p, m -> m.name().endsWith("_PLANKS"));
        }
        if (planks >= 24) { state.phase = Phase.BUILD_HOME; state.target = state.home; return; }

        Block tree = nearestLog(p.getLocation(), 18);
        if (tree == null) {
            wander(p, state, 28);
            if (++state.idleTicks > 45) { state.idleTicks = 0; state.target = randomNearby(p.getLocation(), 48, 110); }
            return;
        }
        state.idleTicks = 0;
        moveToward(p, center(tree.getLocation()), 0.28);
        if (p.getLocation().distanceSquared(center(tree.getLocation())) < 16) {
            ItemStack tool = p.getInventory().getItemInMainHand();
            Collection<ItemStack> drops = tree.getDrops(tool, p);
            tree.setType(Material.AIR, false);
            for (ItemStack drop : drops) p.getInventory().addItem(drop);
        }
    }

    private void buildHome(Bot bot, BotState state) {
        Player p = bot.player;
        Location home = state.home;
        if (home == null || !home.getWorld().equals(p.getWorld())) { state.phase = Phase.CHOOSE_HOME; return; }
        if (p.getLocation().distanceSquared(home) > 25) { moveToward(p, home.clone().add(0, 0, 0), 0.30); return; }

        Material plank = firstPlank(p);
        if (plank == null) { state.phase = Phase.GATHER_WOOD; return; }
        int used = 0;
        World w = home.getWorld();
        int bx = home.getBlockX(), by = home.getBlockY(), bz = home.getBlockZ();
        // Compact 5x5 shelter: plank floor, 3-high perimeter walls, simple roof.
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            Block floor = w.getBlockAt(bx+x, by, bz+z);
            if (floor.getType().isAir() || floor.isPassable()) { floor.setType(plank, false); used++; }
            boolean edge = Math.abs(x) == 2 || Math.abs(z) == 2;
            if (edge) for (int y = 1; y <= 3; y++) {
                if (x == 0 && z == -2 && y <= 2) continue;
                Block wall = w.getBlockAt(bx+x, by+y, bz+z);
                if (wall.getType().isAir()) { wall.setType(plank, false); used++; }
            }
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            Block roof = w.getBlockAt(bx+x, by+4, bz+z);
            if (roof.getType().isAir()) { roof.setType(plank, false); used++; }
        }
        consume(p, plank, Math.min(used, count(p, m -> m == plank)));
        state.built = true;
        state.phase = Phase.EXPLORE;
        saveState(bot.name, state);
        p.sendMessage("§a[NEXORA] Your own survival shelter is ready!");
    }

    private void explore(Bot bot, BotState state) {
        Player p = bot.player;
        if (state.target == null || p.getLocation().distanceSquared(state.target) < 16) {
            state.target = randomNearby(state.home == null ? p.getLocation() : state.home, 30, 100);
        }
        moveToward(p, state.target, 0.24);
        // Keep returning to the bot's own home occasionally.
        if (state.home != null && p.getLocation().distanceSquared(state.home) > 180L * 180L) {
            state.target = state.home.clone();
        }
        // Pick up drops near the bot, then store them in its own inventory.
        for (Item item : p.getWorld().getEntitiesByClass(Item.class)) {
            if (item.getLocation().distanceSquared(p.getLocation()) < 4) {
                ItemStack stack = item.getItemStack().clone();
                Map<Integer, ItemStack> left = p.getInventory().addItem(stack);
                if (left.isEmpty()) item.remove();
            }
        }
        if (count(p, m -> m.name().endsWith("_LOG")) >= 8) state.phase = Phase.GATHER_WOOD;
    }

    private BotState loadState(String name, Location fallback) {
        String path = "survival.homes." + name;
        String world = plugin.getConfig().getString(path + ".world");
        if (world == null || Bukkit.getWorld(world) == null) return new BotState(null);
        World w = Bukkit.getWorld(world);
        Location home = new Location(w, plugin.getConfig().getDouble(path + ".x"),
                plugin.getConfig().getDouble(path + ".y"), plugin.getConfig().getDouble(path + ".z"));
        BotState s = new BotState(home);
        s.built = plugin.getConfig().getBoolean(path + ".built", false);
        s.phase = s.built ? Phase.EXPLORE : Phase.GATHER_WOOD;
        return s;
    }

    private void saveState(String name, BotState s) {
        String path = "survival.homes." + name;
        if (s.home == null) return;
        plugin.getConfig().set(path + ".world", s.home.getWorld().getName());
        plugin.getConfig().set(path + ".x", s.home.getX());
        plugin.getConfig().set(path + ".y", s.home.getY());
        plugin.getConfig().set(path + ".z", s.home.getZ());
        plugin.getConfig().set(path + ".built", s.built);
        plugin.saveConfig();
    }

    private Location chooseHome(Player p) {
        World w = p.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 40; i++) {
            double angle = r.nextDouble(0, Math.PI * 2), distance = r.nextDouble(120, 850);
            int x = p.getLocation().getBlockX() + (int)(Math.cos(angle) * distance);
            int z = p.getLocation().getBlockZ() + (int)(Math.sin(angle) * distance);
            int y = w.getHighestBlockYAt(x, z) + 1;
            Location candidate = new Location(w, x + .5, y, z + .5);
            if (w.getBlockAt(x, y, z).isPassable()
                    && w.getBlockAt(x, y+1, z).isPassable()
                    && w.getBlockAt(x, y-1, z).getType().isSolid()
                    && states.values().stream().filter(s -> s.home != null && s.home.getWorld().equals(w))
                    .noneMatch(s -> s.home.distanceSquared(candidate) < 120L*120L)) return candidate;
        }
        return p.getLocation().clone().add(64, 0, 64);
    }

    private Location randomNearby(Location from, int min, int max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double a = r.nextDouble(Math.PI*2), d = r.nextDouble(min, max);
        int x = from.getBlockX() + (int)(Math.cos(a)*d), z = from.getBlockZ() + (int)(Math.sin(a)*d);
        return new Location(from.getWorld(), x+.5, from.getWorld().getHighestBlockYAt(x,z)+1, z+.5);
    }

    private Block nearestLog(Location at, int radius) {
        Block best = null; double bestD = Double.MAX_VALUE;
        for (int x=-radius; x<=radius; x++) for (int y=-5; y<=8; y++) for (int z=-radius; z<=radius; z++) {
            Block b = at.getWorld().getBlockAt(at.getBlockX()+x, at.getBlockY()+y, at.getBlockZ()+z);
            if (!b.getType().name().endsWith("_LOG")) continue;
            double d = b.getLocation().distanceSquared(at);
            if (d < bestD) { bestD = d; best = b; }
        }
        return best;
    }

    private void wander(Player p, BotState s, int radius) {
        if (s.target == null || p.getLocation().distanceSquared(s.target) < 9) s.target = randomNearby(p.getLocation(), 8, radius);
        moveToward(p, s.target, .23);
    }

    private void moveToward(Player p, Location target, double speed) {
        if (target == null || !target.getWorld().equals(p.getWorld())) return;
        Vector v = target.toVector().subtract(p.getLocation().toVector());
        v.setY(Math.max(-.18, Math.min(.18, v.getY() * .08)));
        if (v.lengthSquared() > .01) {
            v.normalize().multiply(speed);
            p.setVelocity(new Vector(v.getX(), p.getVelocity().getY(), v.getZ()));
            p.setRotation((float)Math.toDegrees(Math.atan2(-v.getX(), v.getZ())), p.getLocation().getPitch());
        }
    }

    private Location center(Location l) { return l.clone().add(.5, .5, .5); }
    private int count(Player p, java.util.function.Predicate<Material> filter) {
        int n=0; for (ItemStack i:p.getInventory().getContents()) if(i!=null&&filter.test(i.getType())) n+=i.getAmount(); return n;
    }
    private void consume(Player p, Material m, int amount) {
        for(int i=0;i<p.getInventory().getSize()&&amount>0;i++){
            ItemStack it=p.getInventory().getItem(i); if(it==null||it.getType()!=m)continue;
            int take=Math.min(amount,it.getAmount()); it.setAmount(it.getAmount()-take); amount-=take;
            if(it.getAmount()<=0)p.getInventory().setItem(i,null);
        }
    }
    private Material firstPlank(Player p) {
        for(ItemStack i:p.getInventory().getContents()) if(i!=null&&i.getType().name().endsWith("_PLANKS"))return i.getType();
        return null;
    }
    private Material plankFor(Material log) {
        String n=log.name();
        if(n.equals("BAMBOO_BLOCK"))return Material.BAMBOO_PLANKS;
        if(!n.endsWith("_LOG"))return null;
        String wood=n.substring(0,n.length()-4);
        try{return Material.valueOf(wood+"_PLANKS");}catch(IllegalArgumentException ex){return null;}
    }

    private record Bot(String name, Player player) {}
    private List<Bot> findBots() {
        List<Bot> found=new ArrayList<>();
        Plugin fb=Bukkit.getPluginManager().getPlugin("FightBot");
        if(fb==null||!fb.isEnabled())return found;
        try{
            Object manager=field(fb,"manager"), raw=field(manager,"bots");
            if(!(raw instanceof Map<?,?> map))return found;
            for(Object rec:map.values()){
                Object n=field(rec,"name"), body=field(rec,"body");
                if(n instanceof String name && body instanceof Player player && player.isOnline() && player.isValid())
                    found.add(new Bot(name,player));
            }
        }catch(ReflectiveOperationException|RuntimeException ex){
            plugin.getLogger().warning("Survival controller could not read FightBot records: "+ex.getMessage());
        }
        return found;
    }
    private Object field(Object obj,String name)throws ReflectiveOperationException{
        if(obj==null)return null;
        for(Class<?> c=obj.getClass();c!=null;c=c.getSuperclass()){
            try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(obj);}
            catch(NoSuchFieldException ignored){}
        }
        throw new NoSuchFieldException(name);
    }
}
