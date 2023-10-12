package ly.count.sdk.java.internal;

public interface IKeyValueStorage<T, R> {

    /**
     * If key exists changes its value, otherwise adds new key-value pair
     *
     * @param key to set
     * @param value to add
     */
    void add(T key, R value);

    void save();

    /**
     * Removes key-value pair
     *
     * @param key to remove
     */
    void delete(T key);

    void addAndSave(T key, R value);

    void deleteAndSave(T key);
}
