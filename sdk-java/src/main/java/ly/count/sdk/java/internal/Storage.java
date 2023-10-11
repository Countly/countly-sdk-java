package ly.count.sdk.java.internal;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Storing and retrieving data from internal storage of SDK.
 * Thread safety is based on single thread of execution - only one thread
 * works with storage at a particular time thanks to {@link Tasks}.
 */

public class Storage {

    private static Tasks tasks;

    static void init() {
        tasks = new Tasks("storage", null);
    }

    public static String name(Storable storable) {
        return storable.storagePrefix() + "_" + storable.storageId();
    }

    /**
     * Stores data in device internal memory. When a storable with the same id already exists,
     * replaces it with new data.
     *
     * @param ctx context to run in
     * @param storable Object to store
     * @return true when storing succeeded, false otherwise
     */
    public static boolean push(InternalConfig config, Storable storable) {
        config.getLogger().d("[Storage] push: " + name(storable) + " " + storable);
        try {
            return pushAsync(config, storable).get();
        } catch (InterruptedException | ExecutionException e) {
            config.getLogger().e("[Storage] Interrupted while pushing " + name(storable) + " " + e);
        }
        return false;
    }

    /**
     * Stores data in device internal memory. When a storable with the same id already exists,
     * replaces it with new data. Runs in a storage thread provided by {@link Tasks}
     *
     * @param ctx context to run in
     * @param storable Object to store
     * @param callback nullable callback to call when done
     * @return Future<Boolean> object which resolves as true when storing succeeded, false otherwise
     */
    public static Future<Boolean> pushAsync(InternalConfig config, final Storable storable, Tasks.Callback<Boolean> callback) {
        config.getLogger().d("[Storage] pushAsync: " + name(storable) + " " + storable.toString());
        return tasks.run(new Tasks.Task<Boolean>(storable.storageId()) {
            @Override
            public Boolean call() throws Exception {
                return config.sdk.sdkStorage.storableWrite(config, storable);
            }
        }, callback);
    }

    /**
     * Shorthand for {@link #pushAsync(CtxCore, Storable, Tasks.Callback)}
     *
     * @param ctx context to run in
     * @param storable Object to store
     * @return Future<Boolean> object which resolves as true when storing succeeded, false otherwise
     */
    public static Future<Boolean> pushAsync(final InternalConfig config, final Storable storable) {
        config.getLogger().d("[Storage] pushAsync: " + name(storable) + " " + storable.toString());
        return pushAsync(config, storable, null);
    }

    /**
     * Removes storable from storage.
     *
     * @param ctx context to run in
     * @param storable Object to remove
     * @return true if removed, false otherwise
     */
    public static <T extends Storable> Boolean remove(final InternalConfig config, T storable) {
        config.getLogger().d("[Storage] remove: " + name(storable) + " " + storable.toString());
        try {
            return removeAsync(config, storable, null).get();
        } catch (InterruptedException | ExecutionException e) {
            config.getLogger().e("[Storage] Interrupted while removing " + name(storable) + " " + e);
        }
        return null;
    }

    /**
     * Removes storable from storage.
     * Runs in a storage thread provided by {@link Tasks}
     *
     * @param ctx context to run in
     * @param storable Object to remove
     * @return Future<Boolean> object which resolves to true if storable is removed, false otherwise
     */
    public static <T extends Storable> Future<Boolean> removeAsync(final InternalConfig config, final T storable, Tasks.Callback<Boolean> callback) {
        return tasks.run(new Tasks.Task<Boolean>(Tasks.ID_STRICT) {
            @Override
            public Boolean call() throws Exception {
                return config.sdk.sdkStorage.storableRemove(config, storable);
            }
        }, callback);
    }

    /**
     * Reinitializes storable with data stored previously in device internal memory and deletes corresponding file.
     *
     * @param ctx context to run in
     * @param storable Object to reinitialize
     * @return storable object passed as param when restoring succeeded, null otherwise
     */
    public static <T extends Storable> T pop(CtxCore ctx, T storable) {
        ctx.getLogger().d("[Storage] pop: " + name(storable) + " " + storable.toString());
        try {
            return popAsync(ctx, storable).get();
        } catch (InterruptedException | ExecutionException e) {
            ctx.getLogger().e("[Storage] Interrupted while removing " + name(storable) + " " + e);
        }
        return null;
    }

    /**
     * Reinitializes storable with data stored previously in device internal memory.
     * Runs in a storage thread provided by {@link Tasks}
     *
     * @param ctx context to run in
     * @param storable Object to reinitialize
     * @return Future<Storable> object which resolves as object passed as param when restoring succeeded, null otherwise
     */
    public static <T extends Storable> Future<T> popAsync(final CtxCore ctx, final T storable) {
        return tasks.run(new Tasks.Task<T>(-storable.storageId()) {
            @Override
            public T call() throws Exception {
                Boolean result = ctx.getSDK().sdkStorage.storablePop(ctx.getConfig(), storable);
                if (result == null || !result) {
                    return null;
                } else {
                    return storable;
                }
            }
        });
    }

    /**
     * Transform existing {@link Storable}s one-by-one replacing data if needed
     *
     * @param ctx context to run in
     * @param prefix Object to reinitialize
     * @return storable object passed as param when reading succeeded, null otherwise
     */
    static <T extends Storable> boolean transform(final InternalConfig config, final String prefix, final Transformer transformer) {
        config.getLogger().d("[Storage] readAll " + prefix);
        try {
            return tasks.run(new Tasks.Task<Boolean>(Tasks.ID_STRICT) {
                @Override
                public Boolean call() throws Exception {
                    boolean success = true;
                    List<Long> ids = config.sdk.sdkStorage.storableList(config, prefix, 0);
                    for (Long id : ids) {
                        byte data[] = config.sdk.sdkStorage.storableReadBytes(config, prefix, id);
                        if (data != null) {
                            byte transformed[] = transformer.doTheJob(id, data);
                            if (transformed != null) {
                                if (!config.sdk.sdkStorage.storableWrite(config, prefix, id, transformed)) {
                                    success = false;
                                    config.getLogger().e("[Storage] Couldn't write transformed data for " + id);
                                }
                            }
                        } else {
                            success = false;
                            config.getLogger().e("[Storage] Couldn't read data to transform from " + id);
                        }
                    }
                    return success;
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            config.getLogger().e("[Storage] Interrupted while reading all " + prefix + " " + e);
        }
        return false;
    }

    /**
     * Reinitializes storable with data stored previously in device internal memory.
     *
     * @param ctx context to run in
     * @param storable Object to reinitialize
     * @return storable object passed as param when reading succeeded, null otherwise
     */
    public static <T extends Storable> T read(InternalConfig config, T storable) {
        config.getLogger().d("[Storage] read: " + name(storable) + " " + storable.toString());
        try {
            return readAsync(config, storable).get();
        } catch (InterruptedException | ExecutionException e) {
            config.getLogger().e("[Storage] Interrupted while popping " + name(storable) + " " + e);
        }
        return null;
    }

    /**
     * Reinitializes storable with data stored previously in device internal memory.
     * Runs in a storage thread provided by {@link Tasks}
     *
     * @param ctx context to run in
     * @param storable Object to reinitialize
     * @return Future<Storable> object which resolves as object passed as param when reading succeeded, null otherwise
     */
    public static <T extends Storable> Future<T> readAsync(final InternalConfig config, final T storable) {
        return readAsync(config, storable, null);
    }

    /**
     * Reinitializes storable with data stored previously in device internal memory.
     * Runs in a storage thread provided by {@link Tasks}
     *
     * @param ctx context to run in
     * @param storable Object to reinitialize
     * @param callback Callback to call with read result
     * @return Future<Storable> object which resolves as object passed as param when reading succeeded, null otherwise
     */
    public static <T extends Storable> Future<T> readAsync(final InternalConfig config, final T storable, final Tasks.Callback<T> callback) {
        return tasks.run(new Tasks.Task<T>(-storable.storageId()) {
            @Override
            public T call() throws Exception {
                Boolean done = config.sdk.sdkStorage.storableRead(config, storable);
                T ret = null;
                if (done == null || !done) {
                    config.getLogger().e("[Storage] No data for file " + name(storable));
                } else {
                    ret = storable;
                }
                if (callback != null) {
                    callback.call(ret);
                }
                return ret;
            }
        });
    }

    /**
     * Reinitializes first (or last if asc is false) storable with prefix from storable supplied as parameter.
     *
     * @param ctx context to run in
     * @param storable Object to get prefix from
     * @param asc true if reading first storable, false if reading last one
     * @return storable object passed as param when reading succeeded, null otherwise
     */
    public static <T extends Storable> T readOne(InternalConfig config, T storable, boolean asc, Log L) {
        config.getLogger().d("[Storage] readOne: " + name(storable) + " " + storable.toString());

        try {
            return readOneAsync(config, storable, asc, L).get();
        } catch (InterruptedException | ExecutionException e) {
            config.getLogger().e("[Storage] Interrupted while popping " + name(storable) + " " + e);
        }
        return null;
    }

    /**
     * Reinitializes first (or last if asc is false) storable with prefix from storable supplied as parameter.
     * Runs in a storage thread provided by {@link Tasks}
     *
     * @param ctx Ctx to run in
     * @param storable Object to get prefix from
     * @param asc true if reading first storable, false if reading last one
     * @return Future<Storable> object which resolves as object passed as param when reading succeeded, null otherwise
     */
    public static <T extends Storable> Future<T> readOneAsync(final InternalConfig config, final T storable, final boolean asc, final Log L) {
        return tasks.run(new Tasks.Task<T>(-storable.storageId()) {
            @Override
            public T call() throws Exception {
                Map.Entry<Long, byte[]> data = config.sdk.sdkStorage.storableReadBytesOneOf(config, storable, asc);
                if (data == null) {
                    return null;
                }
                storable.setId(data.getKey());
                if (storable.restore(data.getValue(), L)) {
                    return storable;
                } else {
                    return null;
                }
            }
        });
    }

    /**
     * Retrieves ids of all files stored for a specific prefix ({@link Storable#storagePrefix()}.
     * Runs in a storage thread provided by {@link Tasks}, current thread waits for read completion.
     *
     * @param prefix String representing type of storable to list (prefix of file names)
     * @return List<Long> object which resolves as list of storable ids, not null
     */
    public static List<Long> list(InternalConfig config, String prefix) {
        return list(config, prefix, 0);
    }

    /**
     * Retrieves ids of files stored for a specific prefix ({@link Storable#storagePrefix()}.
     * Runs in a storage thread provided by {@link Tasks}, current thread waits for read completion.
     *
     * @param prefix String representing type of storable to list (prefix of file names)
     * @param slice integer controlling number and slice direction of results returned:
     * 0 to return all records
     * 1..N to return first N records ordered from first to last
     * -1..-N to return last N records ordered from last to first
     * @return List<Long> object which resolves as list of storable ids, not null
     */
    public static List<Long> list(InternalConfig config, String prefix, int slice) {
        config.getLogger().d("[Storage] readOne: " + prefix);

        try {
            return listAsync(config, prefix, slice).get();
        } catch (InterruptedException | ExecutionException e) {
            config.getLogger().e("[Storage] Interrupted while listing " + prefix + " " + e);
        }
        return null;
    }

    /**
     * Retrieves ids of files stored for a specific prefix ({@link Storable#storagePrefix()}.
     * Runs in a storage thread provided by {@link Tasks}
     *
     * @param prefix String representing type of storable to list (prefix of file names)
     * @param slice integer controlling number and slice direction of results returned:
     * 0 to return all records
     * 1..N to return first N records ordered from first to last
     * -1..-N to return last N records ordered from last to first
     * @return Future<List < Long>> object which resolves as list of storable ids, not null
     */
    public static Future<List<Long>> listAsync(final InternalConfig config, final String prefix, final int slice) {
        return tasks.run(new Tasks.Task<List<Long>>(Tasks.ID_STRICT) {
            @Override
            public List<Long> call() throws Exception {
                List<Long> list = config.sdk.sdkStorage.storableList(config, prefix, slice);
                list.sort((o1, o2) -> slice >= 0 ? o1.compareTo(o2) : o2.compareTo(o1));
                return list;
            }
        });
    }

    public static void await(Log L) {
        if (L != null) {
            L.d("[Storage] Waiting for storage tasks to complete");
        }
        try {
            tasks.run(new Tasks.Task<Boolean>(Tasks.ID_STRICT) {
                @Override
                public Boolean call() throws Exception {
                    if (L != null) {
                        L.d("[Storage] Waiting for storage tasks to complete DONE");
                    }
                    return null;
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            if (L != null) {
                L.e("[Storage] Interrupted while waiting " + e);
            }
        }
    }

    public static void stop() {
        if (tasks != null) {
            tasks.shutdown();
        }
    }
}
