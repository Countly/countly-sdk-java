package ly.count.sdk.java.internal;

import java.io.*;
import java.nio.channels.FileLock;
import java.util.*;

public class SDK extends SDKCore {

    private static final String FILE_NAME_PREFIX = "[CLY]";
    private static final String FILE_NAME_SEPARATOR = "_";

    @Override
    public void stop(ly.count.sdk.java.internal.CtxCore ctx, boolean clear) {
        super.stop(ctx, clear);
        Storage.await();
        if (clear) {
            storablePurge(ctx, null);
        }
        Storage.stop();
    }

    @Override
    public void onRequest(ly.count.sdk.java.internal.CtxCore ctx, Request request) {
        onSignal(ctx, SDKCore.Signal.Ping.getIndex(), null);
    }

    private static String getName(Storable storable) {
        return getName(storable.storagePrefix(), storable.storageId().toString());
    }

    private static String getName(String ...names) {
        if (names == null || names.length == 0 || Utils.isEmptyOrNull(names[0])) {
            return FILE_NAME_PREFIX;
        } else {
            StringBuilder prefix = new StringBuilder(FILE_NAME_PREFIX);
            for (String name : names) {
                if (name == null) {
                    break;
                }
                prefix.append(FILE_NAME_SEPARATOR).append(name);
            }
            return prefix.toString();
        }
    }

    private static String extractName(String filename, String prefix) {
        if (filename.indexOf(prefix) == 0) {
            return filename.substring(prefix.length());
        } else {
            return null;
        }
    }

    @Override
    public int storablePurge(ly.count.sdk.java.internal.CtxCore context, String prefix) {
        prefix = getName(prefix) + FILE_NAME_SEPARATOR;

        L.i("[SDKStorage] Purging storage for prefix " + prefix);

        int deleted = 0;

        String[] files = getFileList(context);
        for (String file : files) {
            if (file.startsWith(prefix)) {
                if (deleteFile(context, file)) {
                    deleted++;
                }
            }
        }

        return deleted;
    }

    @Override
    public Boolean storableWrite(ly.count.sdk.java.internal.CtxCore context, String prefix, Long id, byte[] data) {
        String filename = getName(prefix, id.toString());

        FileOutputStream stream = null;
        FileLock lock = null;
        try {
            stream = openFileAsOutputStream(context, filename);
            lock = stream.getChannel().tryLock();
            if (lock == null) {
                return false;
            }
            stream.write(data);
            stream.close();
            return true;
        } catch (IOException e) {
            L.w("[SDKStorage] Cannot write data to " + filename + " " + e);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    L.w("[SDKStorage] Couldn't close output stream for " + filename + " " + e);
                }
            }
            if (lock != null && lock.isValid()) {
                try {
                    lock.release();
                } catch (IOException e) {
                    L.w("[SDKStorage] Couldn't release lock for " + filename + " " + e);
                }
            }
        }
        return false;
    }

    @Override
    public <T extends Storable> Boolean storableWrite(ly.count.sdk.java.internal.CtxCore context, T storable) {
        return storableWrite(context, storable.storagePrefix(), storable.storageId(), storable.store());
    }

    private String createFileFullPath(ly.count.sdk.java.internal.CtxCore context, String filename){
        String directoryPath = ((File)context.getContext()).getAbsolutePath();
        return directoryPath + File.separator + filename;
    }

    private FileInputStream openFileAsInputStream(ly.count.sdk.java.internal.CtxCore context, String filename) throws FileNotFoundException {
        File initialFile = new File(createFileFullPath(context, filename));
        return new FileInputStream(initialFile);
    }

    private FileOutputStream openFileAsOutputStream(ly.count.sdk.java.internal.CtxCore context, String filename) throws FileNotFoundException {
        File initialFile = new File(createFileFullPath(context, filename));
        return new FileOutputStream(initialFile);
    }

    @Override
    public byte[] storableReadBytes(ly.count.sdk.java.internal.CtxCore context, String filename) {
        ByteArrayOutputStream buffer = null;
        FileInputStream stream = null;

        try {
            buffer = new ByteArrayOutputStream();

            stream = openFileAsInputStream(context, filename);

            int read;
            byte[] data = new byte[4096];
            while((read = stream.read(data, 0, data.length)) != -1){
                buffer.write(data, 0, read);
            }

            stream.close();

            return buffer.toByteArray();
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            L.e("[SDKStorage] Error while reading file " + filename + " " + e);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    L.e("[SDKStorage] Couldn't close input stream for " + filename + " " + e);
                }
            }
            if (buffer != null) {
                try {
                    buffer.close();
                } catch (IOException e) {
                    L.e("[SDKStorage] Cannot happen" + e);
                }
            }
        }
        return null;
    }

    @Override
    public byte[] storableReadBytes(ly.count.sdk.java.internal.CtxCore ctx, String prefix, Long id) {
        return storableReadBytes(ctx, getName(prefix, id.toString()));
    }

    @Override
    public <T extends Storable> Boolean storableRead(ly.count.sdk.java.internal.CtxCore context, T storable) {
        byte[] data = storableReadBytes(context, getName(storable));
        if (data == null) {
            return null;
        } else {
            return storable.restore(data);
        }
    }

    @Override
    public <T extends Storable> Map.Entry<Long, byte[]> storableReadBytesOneOf(ly.count.sdk.java.internal.CtxCore context, T storable, boolean asc) {
        List<Long> list = storableList(context, storable.storagePrefix(), asc ? 1 : -1);
        if (list.size() > 0) {
            return new AbstractMap.SimpleEntry<>(list.get(0), storableReadBytes(context, getName(storable.storagePrefix(), list.get(0).toString())));
        }
        return null;
    }

    private boolean deleteFile(ly.count.sdk.java.internal.CtxCore context, String filename){
        File file = new File(createFileFullPath(context, filename));
        return file.delete();
    }

    @Override
    public <T extends Storable> Boolean storableRemove(ly.count.sdk.java.internal.CtxCore context, T storable) {
        return deleteFile(context, getName(storable.storagePrefix(), storable.storageId().toString()));
    }

    @Override
    public <T extends Storable> Boolean storablePop(ly.count.sdk.java.internal.CtxCore ctx, T storable) {
        Boolean read = storableRead(ctx, storable);
        if (read == null) {
            return null;
        } else if (read) {
            return storableRemove(ctx, storable);
        }
        return read;
    }

    private String[] getFileList(ly.count.sdk.java.internal.CtxCore context){
        File[] files = ((File)context.getContext()).listFiles();
        if (files == null) {
            return new String[0];
        }

        ArrayList<String> fileNames = new ArrayList<>();

        for (File file : files) {
            if (file.isFile()) {
                fileNames.add(file.getName());
            }
        }

        String[] ret = new String[fileNames.size()];
        return fileNames.toArray(ret);
    }

    @Override
    public List<Long> storableList(ly.count.sdk.java.internal.CtxCore context, String prefix, int slice) {
        if (Utils.isEmptyOrNull(prefix)) {
            L.e("[SDKStorage] Cannot get list of ids without prefix");
        }
        prefix = prefix + FILE_NAME_SEPARATOR;

        String alternativePrefix = FILE_NAME_PREFIX + FILE_NAME_SEPARATOR + prefix;

        List<Long> list = new ArrayList<>();

        String[] files = getFileList(context);
        Arrays.sort(files);

        int max = slice == 0 ? Integer.MAX_VALUE : Math.abs(slice);
        for (int i = 0; i < files.length; i++) {
            int idx = slice >= 0 ? i : files.length - 1 - i;
            String file = files[idx];
            if (file.startsWith(prefix) || file.startsWith(alternativePrefix)) {
                try {
                    String name = extractName(file, file.startsWith(prefix) ? prefix : alternativePrefix);
                    if (name != null) {
                        list.add(Long.parseLong(name));
                    }
                } catch (NumberFormatException nfe) {
                    L.e("[SDKStorage] Wrong file name: " + file + " / " + prefix);
                }
                if (list.size() >= max) {
                    break;
                }
            }
        }
        return list;
    }
}
