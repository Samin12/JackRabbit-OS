package com.resonolabs.feature.genui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.resonolabs.runtime.host.ConversationSyncClient;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Pictures shown in the Voice transcript and on host image cards: camera photos, Mac
 * screenshots ({@code mac_look}) and Mac-generated UIs. Process-wide; main thread only.
 *
 * <p>A picture is named by its blob id ({@code sha256:<hex>}, the same id the conversation
 * sync uses), so a card persisted in {@code cards.json} only stores the ref. Bytes live in
 * {@code files/genui/images/} (newest {@value #MAX_FILES} kept); decoded thumbnails
 * (sample-size decoded, at most {@value #THUMB_MAX_SIDE} px) sit in a small LRU of
 * {@value #THUMB_SLOTS}. {@link #thumb} never blocks or allocates once a thumbnail is cached:
 * a miss returns null (draw a placeholder) and decodes on a worker; listeners hear when it is
 * ready.
 */
public final class GenImages {
    public interface Listener {
        /** Main thread: a thumbnail (or its size) became available. */
        void onImageReady(String ref);
    }

    public static final String REF_PREFIX = "sha256:";
    public static final int THUMB_SLOTS = 6;
    public static final int THUMB_MAX_SIDE = 480;
    static final int MAX_FILES = 40;
    static final int MAX_BYTES = 400_000;
    private static final String LOG_TAG = "GenImages";

    private static GenImages instance;

    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sam-genui-images");
        thread.setDaemon(true);
        return thread;
    });
    private final LinkedHashMap<String, Bitmap> thumbs = new LinkedHashMap<>(THUMB_SLOTS + 2, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            // Not recycled: a frame may still be drawing it; the GC reclaims it.
            return size() > THUMB_SLOTS;
        }
    };
    private final HashMap<String, int[]> sizes = new HashMap<>();
    private final HashSet<String> pending = new HashSet<>();
    private final HashSet<String> missing = new HashSet<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();

    private GenImages(File dir) {
        this.dir = dir;
    }

    public static synchronized GenImages get(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            instance = new GenImages(new File(new File(app.getFilesDir(), "genui"), "images"));
        }
        return instance;
    }

    /** The store if something created it already (renderers without a Context), else null. */
    public static synchronized GenImages peek() {
        return instance;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------ pure helpers

    /** {@code sha256:} + 64 lowercase hex digits. */
    public static boolean validRef(String ref) {
        if (ref == null || ref.length() != REF_PREFIX.length() + 64 || !ref.startsWith(REF_PREFIX)) return false;
        for (int index = REF_PREFIX.length(); index < ref.length(); index++) {
            char c = ref.charAt(index);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }

    /**
     * The largest power-of-two sample size that still decodes at least the size the picture
     * is shown at when fitted into {@code maxWidth} x {@code maxHeight}.
     */
    public static int sampleSize(int width, int height, int maxWidth, int maxHeight) {
        if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) return 1;
        float fit = Math.min(maxWidth / (float) width, maxHeight / (float) height);
        if (fit >= 1f) return 1;
        int sample = 1;
        while (sample * 2 <= 1f / fit) sample *= 2;
        return sample;
    }

    // ------------------------------------------------------------------ store

    /**
     * Keeps {@code bytes} (written to disk on the worker) and returns its ref, or null when
     * they are not a decodable picture or too large. The size is read at once (header only);
     * the thumbnail decodes on the worker.
     */
    public String put(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        String ref = ConversationSyncClient.blobId(bytes);
        sizes.put(ref, new int[]{bounds.outWidth, bounds.outHeight});
        missing.remove(ref);
        // Decode from these bytes even when a disk read of the same ref is still in flight: that
        // read may find no file yet and would otherwise leave the ref "missing" for good.
        boolean decode = !thumbs.containsKey(ref);
        if (decode) pending.add(ref);
        submit(() -> {
            write(ref, bytes);
            if (decode) {
                Bitmap thumb = decode(bytes, THUMB_MAX_SIDE, THUMB_MAX_SIDE);
                main.post(() -> deliverThumb(ref, thumb));
            }
        });
        return ref;
    }

    /** Pixel width, or 0 while unknown (then its size is read in the background). */
    public int width(String ref) {
        int[] size = size(ref);
        return size == null ? 0 : size[0];
    }

    public int height(String ref) {
        int[] size = size(ref);
        return size == null ? 0 : size[1];
    }

    /** height / width, or {@code fallback} while unknown. */
    public float aspect(String ref, float fallback) {
        int[] size = size(ref);
        return size == null || size[0] <= 0 ? fallback : size[1] / (float) size[0];
    }

    private int[] size(String ref) {
        if (!validRef(ref)) return null;
        int[] size = sizes.get(ref);
        if (size == null) thumb(ref); // decoding the thumbnail also learns the size
        return size;
    }

    /**
     * The cached thumbnail, or null (draw a placeholder) while it decodes or when the picture
     * is gone. Allocation-free on a hit.
     */
    public Bitmap thumb(String ref) {
        if (ref == null) return null;
        Bitmap cached = thumbs.get(ref);
        if (cached != null || missing.contains(ref) || pending.contains(ref) || !validRef(ref)) return cached;
        pending.add(ref);
        submit(() -> {
            byte[] bytes = read(ref);
            Bitmap thumb = bytes == null ? null : decode(bytes, THUMB_MAX_SIDE, THUMB_MAX_SIDE);
            int[] size = bytes == null ? null : bounds(bytes);
            main.post(() -> {
                if (size != null) sizes.put(ref, size);
                if (bytes == null) missing.add(ref);
                deliverThumb(ref, thumb);
            });
        });
        return null;
    }

    /** Decodes the picture to fit {@code maxWidth} x {@code maxHeight} (viewer); null on failure. Main thread callback. */
    public void loadFull(String ref, int maxWidth, int maxHeight, Consumer<Bitmap> callback) {
        if (!validRef(ref)) {
            callback.accept(null);
            return;
        }
        submit(() -> {
            byte[] bytes = read(ref);
            Bitmap bitmap = bytes == null ? null : decode(bytes, maxWidth, maxHeight);
            main.post(() -> callback.accept(bitmap));
        });
    }

    /** The stored bytes (e.g. to show a picture to the model again); null when gone. Main thread callback. */
    public void loadBytes(String ref, Consumer<byte[]> callback) {
        if (!validRef(ref)) {
            callback.accept(null);
            return;
        }
        submit(() -> {
            byte[] bytes = read(ref);
            main.post(() -> callback.accept(bytes));
        });
    }

    private void deliverThumb(String ref, Bitmap thumb) {
        pending.remove(ref);
        if (thumb != null) {
            missing.remove(ref);
            thumbs.put(ref, thumb);
            if (!sizes.containsKey(ref)) sizes.put(ref, new int[]{thumb.getWidth(), thumb.getHeight()});
        }
        Listener[] snapshot = listeners.toArray(new Listener[0]);
        for (Listener listener : snapshot) listener.onImageReady(ref);
    }

    private void submit(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // never shut down in practice (process singleton)
        }
    }

    // ------------------------------------------------------------------ worker thread

    private File file(String ref) {
        return new File(dir, ref.substring(REF_PREFIX.length()) + ".img");
    }

    private void write(String ref, byte[] bytes) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File target = file(ref);
            if (target.isFile() && target.length() == bytes.length) {
                target.setLastModified(System.currentTimeMillis());
                return;
            }
            File temp = new File(dir, target.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(bytes);
                output.getFD().sync();
            }
            if (!temp.renameTo(target)) temp.delete();
            prune();
        } catch (Exception error) {
            Log.w(LOG_TAG, "image not saved: " + error.getClass().getSimpleName());
        }
    }

    private void prune() {
        File[] files = dir.listFiles((parent, name) -> name.endsWith(".img"));
        if (files == null || files.length <= MAX_FILES) return;
        Arrays.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        for (int index = MAX_FILES; index < files.length; index++) files[index].delete();
    }

    private byte[] read(String ref) {
        try {
            File source = file(ref);
            if (!source.isFile() || source.length() > MAX_BYTES) return null;
            return Files.readAllBytes(source.toPath());
        } catch (Exception error) {
            return null;
        }
    }

    private static int[] bounds(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        return bounds.outWidth > 0 && bounds.outHeight > 0 ? new int[]{bounds.outWidth, bounds.outHeight} : null;
    }

    private static Bitmap decode(byte[] bytes, int maxWidth, int maxHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight);
            boolean opaque = "image/jpeg".equals(bounds.outMimeType);
            options.inPreferredConfig = opaque ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap != null) bitmap.prepareToDraw();
            return bitmap;
        } catch (OutOfMemoryError | RuntimeException error) {
            Log.w(LOG_TAG, "image not decoded: " + error.getClass().getSimpleName());
            return null;
        }
    }
}
