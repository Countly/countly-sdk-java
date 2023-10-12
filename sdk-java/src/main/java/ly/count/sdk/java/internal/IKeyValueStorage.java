package ly.count.sdk.java.internal;

public interface IKeyValueStorage<T, R> {

    /**
     * If key exists changes its value, otherwise adds new key-value pair
     *
     * @param key to set
     * @param value to add
     */
    void add(T key, R value);

    /**
     * Saves changes to the disk/db/memory
     */
    void save();

    /**
     * Removes key-value pair
     *
     * @param key to remove
     */
    void delete(T key);

    /**
     * Adds key-value pair and saves changes to the disk/db/memory
     *
     * @param key to set
     * @param value to add
     */
    void addAndSave(T key, R value);

    /**
     * Removes key-value pair and saves changes to the disk/db/memory
     *
     * @param key to remove
     */
    void deleteAndSave(T key);

    /**
     * Returns value for the key
     *
     * @param key to get
     * @return value
     */
    R get(T key);
}
