package net.citizensnpcs;

import java.util.Map;

import com.google.common.collect.Maps;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.Storage;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.trait.ShopTrait.NPCShop;

public class StoredShops {
    @Persist(value = "global", reify = true)
    public Map<String, NPCShop> globalShops = Maps.newHashMap();
    @Persist(value = "npc", reify = true)
    public Map<String, NPCShop> npcShops = Maps.newHashMap();
    private final Storage storage;

    public StoredShops(YamlStorage storage) {
        this.storage = storage;
    }

    public void load() {
        PersistenceLoader.load(this, storage.getKey(""));
    }

    public boolean loadFromDisk() {
        return storage.load();
    }

    public void saveShops() {
        PersistenceLoader.save(this, storage.getKey(""));
    }

    public void saveToDisk() {
        storage.save();
    }
}