package com.bastionmc.mines;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import java.util.ArrayList;
import java.util.List;

/**
 * WorldGuard defines the mining permission within the exact X/Z polygon and
 * the mine's Y interval. This is not a replacement for the user's PvP region.
 * Auxiliary WG regions are named bastionmine_<mineId> and never use 'minapvp'.
 */
final class WorldGuardMineBridge {
    private static final String PREFIX = "bastionmine_";
    private final BastionMCMines plugin;

    WorldGuardMineBridge(BastionMCMines plugin) { this.plugin = plugin; }

    private RegionManager manager(BastionMCMines.Mine mine) {
        World world = Bukkit.getWorld(mine.world);
        if (world == null) return null;
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
    }

    boolean sync(BastionMCMines.Mine mine) {
        try {
            RegionManager manager = manager(mine);
            if (manager == null) {
                plugin.getLogger().warning("Mundo de mina no cargado: " + mine.world);
                return false;
            }
            List<BlockVector2> points = new ArrayList<>();
            for (BastionMCMines.Point point : mine.points) points.add(BlockVector2.at(point.x, point.z));
            if (points.size() < 3) return false;
            String id = PREFIX + mine.id;
            ProtectedRegion old = manager.getRegion(id);
            if (old != null && !(old instanceof ProtectedPolygonalRegion)) {
                plugin.getLogger().severe("Conflicto: existe una región ajena con ID " + id);
                return false;
            }
            // Do not touch the square PvP region or any manually built region.
            ProtectedPolygonalRegion region = new ProtectedPolygonalRegion(
                id, points, mine.top - mine.depth + 1, mine.top);
            int priority = Math.max(11, plugin.getConfig().getInt("worldguard.minimum-mine-priority", 100));
            // Never lower an existing auxiliary region's priority.
            if (old != null) priority = Math.max(priority, old.getPriority());
            // Higher priority than overlapping WG regions, except our own.
            for (ProtectedRegion other : manager.getApplicableRegions(region)) {
                if (other.getId().equals(id)) continue;
                if (other.getPriority() >= priority && other.getPriority() < Integer.MAX_VALUE)
                    priority = other.getPriority() + 1;
            }
            region.setPriority(priority);
            region.setFlag(Flags.BLOCK_BREAK, StateFlag.State.ALLOW);
            region.setFlag(Flags.BLOCK_PLACE, StateFlag.State.DENY);
            // Do NOT set PvP, use, entry, mob-spawning or other unrelated flags.
            manager.addRegion(region);
            manager.save();
            plugin.getLogger().info("WG zona minable " + id + " (" + mine.world
                    + ") sincronizada; prioridad " + priority + ", Y "
                    + (mine.top-mine.depth+1) + ".." + mine.top);
            return true;
        } catch (Exception ex) {
            plugin.getLogger().severe("Fallo creando región WG para " + mine.id + ": " + ex.getMessage());
            return false;
        }
    }

    void remove(BastionMCMines.Mine mine) {
        try {
            RegionManager manager = manager(mine);
            if (manager == null) return;
            manager.removeRegion(PREFIX + mine.id);
            manager.save();
        } catch (Exception ex) {
            plugin.getLogger().warning("No se pudo eliminar la región WG auxiliar " + mine.id + ": " + ex.getMessage());
        }
    }
}
