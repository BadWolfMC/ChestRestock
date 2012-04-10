package com.dumptruckman.chestrestock;

import com.dumptruckman.chestrestock.api.CRChest;
import com.dumptruckman.chestrestock.api.CRConfig;
import com.dumptruckman.chestrestock.api.ChestManager;
import com.dumptruckman.chestrestock.util.BlockLocation;
import com.dumptruckman.chestrestock.util.Language;
import com.dumptruckman.minecraft.pluginbase.util.Logging;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.InventoryHolder;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

class DefaultChestManager implements ChestManager {

    private final static String EXT = ".yml";

    private ChestRestockPlugin plugin;
    private File chestsFile;
    
    private Map<BlockLocation, CRChest> chestsMap = new HashMap<BlockLocation, CRChest>();
    private Set<CRChest> pollingSet = new LinkedHashSet<CRChest>();
    
    DefaultChestManager(ChestRestockPlugin plugin) {
        this.plugin = plugin;
        chestsFile = new File(plugin.getDataFolder(), "chests");
        if (!chestsFile.exists()) {
            chestsFile.mkdirs();
        }
        initPolling();
    }

    private void initPolling() {
        if (plugin.config().get(CRConfig.RESTOCK_TASK) < 1) {
            Logging.fine("Chest restock polling disabled");
            return;
        }
        Logging.fine("Initializing chest polling.");
        File worldContainer = Bukkit.getWorldContainer();
        for (File file : worldContainer.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.isDirectory();
            }
        })) {
            File worldFolder = getWorldFolder(file.getName());
            if (worldFolder.exists()) {
                Logging.finer("Checking chests for world: " + worldFolder);
                for (File chestFile : worldFolder.listFiles(new FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        return name.endsWith(EXT);
                    }
                })) {
                    CRChest chest = loadChest(chestFile);
                    if (chest != null) {
                        Logging.finest("Polling check in for " + chest.getLocation());
                        pollingCheckIn(chest);
                        chestsMap.put(chest.getLocation(), chest);
                    }
                }
            }
        }
    }
    
    private File getWorldFolder(String worldName) {
        return new File(chestsFile, worldName);
    }
    
    private File getChestFile(BlockLocation location) {
        return new File(getWorldFolder(location.getWorldName()), location.toString() + EXT);
    }

    @Override
    public CRChest getChest(Block block, InventoryHolder holder) {
        BlockLocation location = BlockLocation.get(block);
        CRChest rChest = chestsMap.get(location);
        if (rChest != null) {
            Logging.finer("Got cached chest at " + location);
            return rChest;
        }
        File chestFile = getChestFile(location);
        if (!chestFile.exists()) {
            if (!(holder.getInventory() instanceof DoubleChestInventory)) {
                Logging.finest("No file for single chest found.");
                return null;
            }
            Logging.finer("Searching for other side of double chest...");
            Chest otherSide = getOtherSide(block);
            if (otherSide == null) {
                Logging.fine("Chest claims to be double but other side not found!");
                return null;
            }
            location = BlockLocation.get(otherSide.getBlock());
            rChest = chestsMap.get(location);
            if (rChest != null) {
                Logging.finer("Got cached chest (other-side) at " + location);
                return rChest;
            }
            chestFile = getChestFile(location);
            if (!chestFile.exists()) {
                Logging.finest("No file found for other side of double chest.");
                return null;
            }
        }
        rChest = loadChest(chestFile);
        chestsMap.put(location, rChest);
        return rChest;
    }
    
    //public void removeChest()

    @Override
    public CRChest newChest(Block block, InventoryHolder holder) {
        return loadChest(getChestFile(BlockLocation.get(block)));
    }

    @Override
    public boolean removeChest(BlockLocation location) {
        boolean cached = chestsMap.remove(location) != null;
        boolean filed = getChestFile(location).delete();
        if (cached || filed) {
            Logging.fine("Removed chest from cache: " + cached + ".  Able to delete file: " + filed);
            return true;
        }
        Block chestBlock = location.getBlock();
        if (chestBlock != null) {
            Chest chest = getOtherSide(chestBlock);
            if (chest != null) {
                location = BlockLocation.get(chest.getBlock());
                cached = chestsMap.remove(location) != null;
                filed = getChestFile(location).delete();
                if (cached || filed) {
                    Logging.fine("Removed chest from cache: " + cached + ".  Able to delete file: " + filed);
                    return true;
                }
            }
        }
        Logging.fine("Found no chest to remove in cache or files");
        return false;
    }

    private CRChest loadChest(File chestFile) {
        Logging.fine("Loading chest from file: " + chestFile.getName());
        try {
            BlockLocation location = BlockLocation.get(
                    chestFile.getName().substring(0, chestFile.getName().indexOf(EXT)));
            if (location == null) {
                Logging.warning("Block location could not be parsed from file name: " + chestFile.getName());
                return null;
            }
            return new DefaultCRChest(plugin, location, chestFile, CRChest.class);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } catch (ArrayIndexOutOfBoundsException e) {
            Logging.warning("Block location could not be parsed from file name: " + chestFile.getName());
            return null;
        } catch (IllegalStateException e) {
            Logging.warning(e.getMessage());
            return null;
        }
    }

    @Override
    public Chest getOtherSide(Block chestBlock) {
        Chest otherSide = null;
        if (chestBlock.getRelative(1, 0, 0).getState() instanceof Chest) {
            otherSide = (Chest) chestBlock.getRelative(1, 0, 0).getState();
        } else if (chestBlock.getRelative(-1, 0, 0).getState() instanceof Chest) {
            otherSide = (Chest) chestBlock.getRelative(-1, 0, 0).getState();
        } else if (chestBlock.getRelative(0, 0, 1).getState() instanceof Chest) {
            otherSide = (Chest) chestBlock.getRelative(0, 0, 1).getState();
        } else if (chestBlock.getRelative(0, 0, -1).getState() instanceof Chest) {
            otherSide = (Chest) chestBlock.getRelative(0, 0, -1).getState();
        }
        return otherSide;
    }

    @Override
    public Block getTargetedInventoryHolder(Player player) throws IllegalStateException {
        Block block = player.getTargetBlock(null, 100);
        if (block == null || !(block.getState() instanceof InventoryHolder)) {
            throw new IllegalStateException(plugin.getMessager().getMessage(Language.TARGETING));
        }
        return block;
    }

    @Override
    public boolean pollingCheckIn(CRChest chest) {
        if (chest.get(CRChest.ACCEPT_POLL)) {
            if (pollingSet.add(chest)) {
                Logging.finest(chest.getLocation() + " added to polling");
            }
            return true;
        } else {
            if (pollingSet.remove(chest)) {
                Logging.finest(chest.getLocation() + " removed to polling");
            }
            return false;
        }
    }

    @Override
    public Set<CRChest> getChestsForPolling() {
        return pollingSet;
    }

    @Override
    public void pollChests() {
        Iterator<CRChest> it = getChestsForPolling().iterator();
        while (it.hasNext()) {
            CRChest chest = it.next();
            if (chest.getInventoryHolder() == null) {
                it.remove();
                continue;
            }
            chest.openInventory(null);
        }
    }
}
