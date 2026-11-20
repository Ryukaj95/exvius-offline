package com.exvius.local;

import android.app.Activity;
import android.app.Application;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** One complete first download, cached offline play, and static release updates. */
public final class DataUpdates {
    private static final String BOOTSTRAP = "https://raw.githubusercontent.com/Ryukaj95/exvius-offline/b520e563cbc4364a07ee40331012340dc8aeb841/data/latest.json";
    private static final String BOOTSTRAP_SHA256 = "3f77a7c1f7e13dfbd9343fcb023a2cd609c7669bd44fec09d530cf25881ad093";
    private static final String SIGNED_LATEST = "https://raw.githubusercontent.com/Ryukaj95/exvius-offline/main/data/latest.signed.json";
    private static final String UPSTREAM_ASSETS = "https://github.com/Elvlin/exvius-offline/releases/download/";
    private static final String FORK_ASSETS = "https://github.com/Ryukaj95/exvius-offline/releases/download/";
    private static final int MAX_MANIFEST_BYTES = 524288;
    private static final int MAX_UPDATE_INFO_BYTES = 1048576;
    private static final String PENDING = "pending-authenticated.json";
    private static final String JOURNAL = "install-progress-authenticated.json";
    // This native app requires the external rules and content introduced in revision 13 (weapon enhancement pages).
    // Raise it whenever the engine needs files from a newer data revision.
    private static final long REQUIRED_DATA_REVISION = 13;
    private static final AtomicBoolean working = new AtomicBoolean();
    private static volatile Attempt activeAttempt;
    private static boolean retryRequested;
    private static volatile Progress listener;
    private static boolean initialized, gameOpened;

    public interface Progress {
        void update(String message, long done, long total, boolean retryAvailable);
        void complete();
        void failed(String message);
    }
    private DataUpdates() {}

    /** One worker owns the files. Its monitor can cancel blocked network IO. */
    private static final class Attempt implements AutoCloseable {
        final Thread worker;
        final Thread monitor;
        volatile boolean cancelled;
        private boolean closed;
        private boolean waiting;
        private HttpURLConnection connection;
        private long lastData, done, total;
        private String message;
        private long idleSeconds;

        Attempt(Thread worker) {
            this.worker = worker;
            monitor = new Thread(() -> {
                for (;;) {
                    HttpURLConnection disconnect = null;
                    synchronized (this) {
                        if (closed) return;
                        if (cancelled) {
                            disconnect = connection;
                            if (disconnect == null) return;
                        } else if (waiting) {
                            long seconds = (System.nanoTime() - lastData) / 1000000000L;
                            while (idleSeconds < seconds) {
                                idleSeconds++;
                                Progress current = listener;
                                if (current != null) current.update(message +
                                    "\nNo answer from the server for " + idleSeconds + " s",
                                    done, total, idleSeconds >= 10);
                            }
                        }
                        if (disconnect == null) {
                            long delay = !waiting ? 1000 : 1000 -
                                ((System.nanoTime() - lastData) / 1000000L) % 1000;
                            try { wait(delay); }
                            catch (InterruptedException ignored) {}
                        }
                    }
                    if (disconnect != null) {
                        disconnect.disconnect();
                        // Cancellation may race the platform's connection initialization.
                        synchronized (this) {
                            if (closed) return;
                            try { wait(100); }
                            catch (InterruptedException ignored) {}
                        }
                    }
                }
            }, "ExviusDownloadMonitor");
        }
        synchronized void status(String text, long completed, long size) {
            message = text;
            done = completed;
            total = size;
            Progress current = listener;
            if (!cancelled && current != null) current.update(waiting && idleSeconds > 0 ?
                text + "\nNo answer from the server for " + idleSeconds + " s" : text,
                completed, size, waiting && idleSeconds >= 10);
        }
        synchronized void connect(HttpURLConnection value) throws IOException {
            checkCancelled();
            connection = value;
            if (!waiting) {
                waiting = true;
                lastData = System.nanoTime();
                idleSeconds = 0;
            }
            notifyAll();
        }
        synchronized void received() throws IOException {
            checkCancelled();
            lastData = System.nanoTime();
            if (idleSeconds > 0) {
                idleSeconds = 0;
                status(message, done, total);
            }
        }
        synchronized void disconnected() {
            connection = null;
        }
        synchronized void finishedTransfer() {
            waiting = false;
            idleSeconds = 0;
            status(message, done, total);
        }
        synchronized void cancel() {
            cancelled = true;
            worker.interrupt();
            monitor.interrupt();
        }
        @Override public void close() {
            synchronized (this) { closed = true; }
            monitor.interrupt();
            // Do not leave an old monitor disconnecting while a replacement starts.
            boolean interrupted = Thread.interrupted();
            for (;;) {
                try { monitor.join(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted && !cancelled) worker.interrupt();
        }
    }
    private static void checkCancelled() throws IOException {
        Attempt attempt = activeAttempt;
        if (attempt != null && attempt.cancelled) throw new IOException("Download cancelled");
    }
    private static void received() throws IOException {
        Attempt attempt = activeAttempt;
        if (attempt != null) attempt.received();
    }
    private static void connect(HttpURLConnection connection) throws IOException {
        Attempt attempt = activeAttempt;
        if (attempt != null) attempt.connect(connection);
    }
    private static void disconnected() {
        Attempt attempt = activeAttempt;
        if (attempt != null) attempt.disconnected();
    }
    private static void finishedTransfer() {
        Attempt attempt = activeAttempt;
        if (attempt != null) attempt.finishedTransfer();
    }

    static synchronized void initialize(Context context) {
        if (initialized) return;
        initialized = true;
        ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(
            new Application.ActivityLifecycleCallbacks() {
                public void onActivityResumed(Activity activity) {}
                public void onActivityPaused(Activity activity) {}
                public void onActivityCreated(Activity activity, Bundle state) {
                    if (activity.getClass().getName().equals("com.square_enix.android_googleplay.FFBEWW.Lapis"))
                        gameOpened = true;
                }
                public void onActivityStarted(Activity activity) {}
                public void onActivityStopped(Activity activity) {}
                public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                public void onActivityDestroyed(Activity activity) {}
            });
    }

    private static File state(Context context, String name) {
        return new File(context.getFilesDir(), "exvius/data/" + name);
    }
    public static File dataRoot(Context context) { return state(context, "static"); }
    public static boolean gameOpened() { return gameOpened; }
    public static boolean hasPending(Context context) { return state(context, PENDING).isFile(); }
    public static boolean clientAssetsMissing(Context context) {
        return installedManifestComplete(context) && !GameAssets.archive(context).isFile();
    }
    private static boolean installedManifestComplete(Context context) {
        try {
            JSONObject current = installed(context);
            if (current.optLong("revision", 0) < REQUIRED_DATA_REVISION) return false;
            JSONArray packs = current.optJSONArray("packs");
            if (packs == null || packs.length() < 1) return false;
            for (int i = 0; i < packs.length(); i++) {
                JSONObject pack = packs.getJSONObject(i);
                if (pack.optString("id").isEmpty() ||
                    !pack.optString("sha256").matches("[a-f0-9]{64}") ||
                    pack.optLong("unpackedSize", 0) < 1) return false;
            }
            return true;
        } catch (Exception error) {
            android.util.Log.w("ExviusUpdates", "Installed data manifest is unreadable", error);
            return false;
        }
    }
    public static boolean ready(Context context) {
        return !hasPending(context) &&
            installedManifestComplete(context) &&
            GameAssets.archive(context).isFile() &&
            new File(dataRoot(context), "mst_decoded/F_UNIT_MST.ndjson").isFile() &&
            new File(context.getFilesDir(), "mst/Ver9001_YW9MUm2H.dat").isFile();
    }
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("offline_updates", Context.MODE_PRIVATE);
    }
    private static DownloadManager downloads(Context context) {
        return (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
    }
    private static boolean online(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    private static int appVersion(Context context) throws Exception {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
    }
    private static String read(File file) throws IOException {
        if (file.length() > MAX_UPDATE_INFO_BYTES) throw new IOException("Update information is too large");
        try (FileInputStream input = new FileInputStream(file)) {
            return new String(readUpdateInfo(input), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readUpdateInfo(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            received();
            if (output.size() + count > MAX_UPDATE_INFO_BYTES) throw new IOException("Update information is too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static JSONObject fetchLatest() throws Exception {
        String publicKey = BuildConfig.UPDATE_PUBLIC_KEY;
        String url = publicKey.isEmpty() ? BOOTSTRAP : SIGNED_LATEST;
        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            checkCancelled();
            progress(attempt == 1 ? "Checking for game data..." :
                "Checking for game data... (attempt " + attempt + " of 3)", 0, 0);
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Accept-Encoding", "identity");
                try {
                    connect(connection);
                    int code = connection.getResponseCode();
                    received();
                    if (code != HttpURLConnection.HTTP_OK)
                        throw new IOException("Game data server returned HTTP " + code);
                    try (InputStream input = connection.getInputStream()) {
                        byte[] response = readUpdateInfo(input);
                        JSONObject manifest = authenticateManifest(response, publicKey);
                        validate(manifest);
                        finishedTransfer();
                        return manifest;
                    }
                } finally {
                    connection.disconnect();
                    disconnected();
                }
            } catch (IOException | org.json.JSONException | java.security.GeneralSecurityException | IllegalArgumentException error) {
                checkCancelled();
                lastError = error;
                if (attempt < 3) Thread.sleep(attempt == 1 ? 2000 : 5000);
            }
        }
        throw new IOException("The game data check failed after 3 attempts. Check your connection and tap Retry. " +
            lastError.getMessage(), lastError);
    }
    private static JSONObject authenticateManifest(byte[] response, String publicKey) throws Exception {
        byte[] payload = response;
        if (publicKey.isEmpty()) {
            if (!UpdateIntegrity.matchesSha256(payload, BOOTSTRAP_SHA256))
                throw new IOException("Bootstrap game data manifest failed verification");
        } else {
            JSONObject envelope = new JSONObject(new String(response, StandardCharsets.UTF_8));
            payload = android.util.Base64.decode(envelope.getString("manifest"), android.util.Base64.NO_WRAP);
            byte[] signature = android.util.Base64.decode(envelope.getString("signature"), android.util.Base64.NO_WRAP);
            byte[] key = android.util.Base64.decode(publicKey, android.util.Base64.NO_WRAP);
            if (payload.length > MAX_MANIFEST_BYTES || !UpdateIntegrity.verify(payload, signature, key))
                throw new IOException("Game data manifest signature is invalid");
        }
        return new JSONObject(new String(payload, StandardCharsets.UTF_8));
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create update folder");
        File partial = new File(parent, file.getName() + ".new");
        try (FileOutputStream output = new FileOutputStream(partial)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            android.system.Os.rename(partial.getPath(), file.getPath());
        } catch (android.system.ErrnoException error) {
            throw new IOException("Cannot save update information", error);
        }
    }
    private static JSONObject installed(Context context) throws Exception {
        File file = state(context, "installed.json");
        return file.isFile() ? new JSONObject(read(file)) : new JSONObject();
    }
    private static boolean installedPack(JSONObject current, JSONObject pack) throws Exception {
        JSONArray packs = current.optJSONArray("packs");
        if (packs == null) return false;
        for (int i = 0; i < packs.length(); i++) {
            JSONObject row = packs.getJSONObject(i);
            if (pack.getString("id").equals(row.optString("id")) &&
                pack.getString("sha256").equals(row.optString("sha256"))) return true;
        }
        return false;
    }
    private static void validate(JSONObject manifest) throws Exception {
        if (manifest.getInt("schema") != 1 || manifest.getLong("revision") < REQUIRED_DATA_REVISION)
            throw new IOException("Unsupported update information");
        JSONArray packs = manifest.getJSONArray("packs");
        if (packs.length() < 1 || packs.length() > 1000) throw new IOException("Invalid data pack list");
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < packs.length(); i++) {
            JSONObject pack = packs.getJSONObject(i);
            if (!pack.getString("id").matches("[a-zA-Z0-9_-]{1,80}") || !ids.add(pack.getString("id")) ||
                !releaseAsset(pack.getString("url")) ||
                !pack.getString("sha256").matches("[a-f0-9]{64}") ||
                pack.getLong("size") < 1 || pack.getLong("size") >= 2147483648L ||
                pack.getLong("unpackedSize") < 1)
                throw new IOException("Invalid data pack information");
        }
    }
    private static boolean releaseAsset(String url) {
        return url.startsWith(UPSTREAM_ASSETS) || url.startsWith(FORK_ASSETS);
    }

    private static File downloadFile(Context context, String key, String suffix) throws IOException {
        File folder = new File(context.getFilesDir(), "exvius/update-downloads");
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Download storage is unavailable");
        return new File(folder, key + suffix);
    }
    private static void progress(String text, long done, long total) {
        Attempt attempt = activeAttempt;
        if (attempt != null) {
            attempt.status(text, done, total);
            return;
        }
        Progress current = listener;
        if (current != null) current.update(text, done, total, false);
    }

    private static File resumableDownload(Context context, String key, String url, String suffix,
                                          long base, long total, long expectedSize, String label) throws Exception {
        String preference = "download_" + key;
        SharedPreferences saved = preferences(context);
        long staleId = saved.getLong(preference, -1);
        if (staleId >= 0) {
            downloads(context).remove(staleId);
            saved.edit().remove(preference).commit();
        }
        File target = downloadFile(context, key, suffix);
        int failuresWithoutProgress = 0;
        for (;;) {
            checkCancelled();
            if (!online(context)) throw new IOException("Connect to the internet to finish the game data installation.");
            long before = target.isFile() ? target.length() : 0;
            try {
                resumeHttp(target, url, base, total, expectedSize, label);
                return target;
            } catch (IOException error) {
                checkCancelled();
                long after = target.isFile() ? target.length() : 0;
                failuresWithoutProgress = after > before ? 0 : failuresWithoutProgress + 1;
                progress(label + "\nResuming after interruption...", base + after, total);
                if (failuresWithoutProgress >= 5) throw error;
                Thread.sleep(2000);
            }
        }
    }

    private static void resumeHttp(File target, String url,
                                   long base, long total, long expectedSize, String label) throws IOException {
        long offset = target.isFile() ? target.length() : 0;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
        progress(downloadMessage(label, base + offset, total, 0), base + offset, total);
        try {
            connect(connection);
            int code = connection.getResponseCode();
            received();
            if (code == 416) {
                finishedTransfer();
                return;
            }
            boolean append;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                append = true;
            } else if (code == HttpURLConnection.HTTP_OK) {
                append = false;
                offset = 0;
            } else {
                throw new IOException("Game data server returned HTTP " + code);
            }
            long responseBytes = connection.getContentLengthLong();
            long expected = responseBytes >= 0 ? offset + responseBytes : -1;
            String contentRange = connection.getHeaderField("Content-Range");
            if (contentRange != null) {
                int slash = contentRange.lastIndexOf('/');
                if (slash >= 0) {
                    try { expected = Long.parseLong(contentRange.substring(slash + 1)); }
                    catch (NumberFormatException ignored) {}
                }
            }
            long completed = offset;
            long speedBytes = offset;
            long speedTime = System.currentTimeMillis();
            double bytesPerSecond = 0;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(target, append)) {
                byte[] buffer = new byte[262144];
                int count;
                long reported = completed;
                while ((count = input.read(buffer)) != -1) {
                    received();
                    if (completed + count > expectedSize)
                        throw new IOException("Download exceeds the authenticated file size");
                    output.write(buffer, 0, count);
                    completed += count;
                    long now = System.currentTimeMillis();
                    if (now - speedTime >= 1000) {
                        bytesPerSecond = (completed - speedBytes) * 1000.0 / (now - speedTime);
                        speedBytes = completed;
                        speedTime = now;
                    }
                    if (completed - reported >= 1048576 || now - speedTime == 0) {
                        reported = completed;
                        progress(downloadMessage(label, base + completed, total, bytesPerSecond),
                            base + completed, total);
                    }
                }
                progress(downloadMessage(label, base + completed, total, bytesPerSecond),
                    base + completed, total);
                output.getFD().sync();
            }
            if (expected >= 0 && completed != expected)
                throw new IOException("The game data connection closed early");
            finishedTransfer();
        } finally {
            connection.disconnect();
            disconnected();
        }
    }

    private static String transferRate(double bytesPerSecond) {
        if (bytesPerSecond < 1) return "measuring speed";
        if (bytesPerSecond >= 1048576)
            return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1048576.0);
        return Math.max(1, Math.round(bytesPerSecond / 1024.0)) + " KB/s";
    }

    private static String downloadMessage(String label, long completed, long total,
                                          double bytesPerSecond) {
        return label + "\n" +
            String.format(Locale.ROOT, "%,d / %,d MB", completed / 1048576, total / 1048576) +
            "\n" + transferRate(bytesPerSecond);
    }

    private static File downloadPack(Context context, JSONObject pack, long base, long total,
                                     int index, int count) throws Exception {
        String hash = pack.getString("sha256"), key = "data_" + hash;
        File file = downloadFile(context, key, ".zip");
        long expectedSize = pack.getLong("size");
        if (!file.isFile() || file.length() != expectedSize)
            file = resumableDownload(context, key, pack.getString("url"), ".zip", base, total, expectedSize,
                "Downloading game data\nPack " + (index + 1) + " of " + count);
        if (file.length() != expectedSize) {
            clearDownload(context, key, ".zip");
            throw new IOException("Downloaded file has the wrong size. Please retry.");
        }
        return file;
    }
    private static void clearDownload(Context context, String key, String suffix) throws Exception {
        long id = preferences(context).getLong("download_" + key, -1);
        if (id >= 0) downloads(context).remove(id);
        preferences(context).edit().remove("download_" + key).commit();
        downloadFile(context, key, suffix).delete();
    }
    private static void installPending(Context context, boolean repairAssets) throws Exception {
        JSONObject manifest = new JSONObject(read(state(context, PENDING)));
        validate(manifest);
        JSONObject current = installed(context);
        if (!canInstall(context, manifest, current)) {
            state(context, PENDING).delete();
            throw new IOException("The pending game data is no longer compatible.");
        }
        JSONArray packs = manifest.getJSONArray("packs");
        File journalFile = state(context, JOURNAL);
        JSONObject journal = journalFile.isFile() ? new JSONObject(read(journalFile)) : new JSONObject();
        JSONArray completed = journal.optLong("revision") == manifest.getLong("revision") ?
            journal.optJSONArray("completed") : null;
        if (completed == null) completed = new JSONArray();
        java.util.HashSet<String> installedHashes = new java.util.HashSet<>();
        for (int i = 0; i < completed.length(); i++) installedHashes.add(completed.getString(i));
        long total = 0, transferred = 0;
        for (int i = 0; i < packs.length(); i++) total += packs.getJSONObject(i).getLong("size");
        for (int i = 0; i < packs.length(); i++) {
            JSONObject pack = packs.getJSONObject(i);
            boolean forcePack = repairAssets && pack.getString("id").equals("client-assets");
            if (!forcePack && (installedPack(current, pack) || installedHashes.contains(pack.getString("sha256")))) {
                transferred += pack.getLong("size");
                continue;
            }
            File archive = downloadPack(context, pack, transferred, total, i, packs.length());
            progress("Checking downloaded game data...", transferred, total);
            if (!DataPack.sha256(archive).equals(pack.getString("sha256"))) {
                clearDownload(context, "data_" + pack.getString("sha256"), ".zip");
                throw new IOException("Game data verification failed. Please retry.");
            }
            progress("Installing game data (" + (i + 1) + "/" + packs.length() + ")...", i, packs.length());
            try {
                DataPack.extract(archive, context.getFilesDir(), pack.getLong("unpackedSize"));
            } catch (IOException error) {
                clearDownload(context, "data_" + pack.getString("sha256"), ".zip");
                throw new IOException("A game data pack could not be installed. Please retry.", error);
            }
            transferred += pack.getLong("size");
            completed.put(pack.getString("sha256"));
            journal.put("revision", manifest.getLong("revision")).put("completed", completed);
            write(journalFile, journal.toString());
            clearDownload(context, "data_" + pack.getString("sha256"), ".zip");
        }
        File registryFile = state(context, "registry.json");
        JSONObject registry = new JSONObject(read(registryFile));
        SharedPreferences.Editor registryEdit = context.getSharedPreferences("gamedata", Context.MODE_PRIVATE).edit();
        Iterator<String> keys = registry.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith("keyF_")) throw new IOException("Invalid resource registry key");
            registryEdit.putString(key, registry.getString(key));
        }
        if (!registryEdit.commit()) throw new IOException("Cannot save game resource versions");
        if (!new File(dataRoot(context), "mst_decoded/F_UNIT_MST.ndjson").isFile())
            throw new IOException("The downloaded game data is incomplete");
        write(state(context, "installed.json"), manifest.toString());
        state(context, PENDING).delete();
        journalFile.delete();
        for (int i = 0; i < packs.length(); i++)
            clearDownload(context, "data_" + packs.getJSONObject(i).getString("sha256"), ".zip");
    }
    public static synchronized void prepare(Context context, Progress progress, boolean repairAssets) {
        listener = progress;
        begin(context.getApplicationContext(), true, repairAssets);
    }
    public static synchronized void retry(Context context, Progress progress, boolean repairAssets) {
        listener = progress;
        if (working.get()) {
            retryRequested = true;
            if (activeAttempt != null) activeAttempt.cancel();
        } else {
            begin(context.getApplicationContext(), true, repairAssets);
        }
    }
    public static void detach(Progress progress) { if (listener == progress) listener = null; }
    public static synchronized void checkInBackground(Context context) {
        listener = null;
        begin(context.getApplicationContext(), false, false);
    }
    private static boolean canInstall(Context context, JSONObject manifest, JSONObject current) throws Exception {
        return manifest.getInt("minAppVersionCode") <= appVersion(context) &&
            manifest.getLong("revision") >= current.optLong("revision");
    }

    private static synchronized void begin(Context context, boolean mandatory, boolean repairAssets) {
        if (!working.compareAndSet(false, true)) return;
        new Thread(() -> {
            for (;;) {
                Attempt attempt = new Attempt(Thread.currentThread());
                synchronized (DataUpdates.class) {
                    activeAttempt = attempt;
                    if (retryRequested) attempt.cancel();
                }
                attempt.monitor.start();
                String failure = null;
                try {
                    if (repairAssets) {
                        if (gameOpened) throw new IOException("Close and reopen Exvius to repair the game assets.");
                        if (!online(context)) throw new IOException("Connect to the internet to repair the game assets.");
                        if (!hasPending(context)) {
                            JSONObject manifest = fetchLatest();
                            if (canInstall(context, manifest, installed(context)))
                                write(state(context, PENDING), manifest.toString());
                            else
                                throw new IOException("No compatible game data is available for repair.");
                        }
                        installPending(context, true);
                    } else if (hasPending(context)) {
                        if (gameOpened)
                            throw new IOException("An update is ready. Fully close Exvius, then open it again to apply the update.");
                        if (mandatory) installPending(context, false);
                    } else {
                        JSONObject manifest = fetchLatest();
                        JSONObject current = installed(context);
                        if (canInstall(context, manifest, current) &&
                            (!ready(context) || manifest.getLong("revision") > current.optLong("revision"))) {
                            if (mandatory) {
                                // First installation needs space for only one downloaded part at a time.
                                write(state(context, PENDING), manifest.toString());
                                installPending(context, false);
                            } else {
                            JSONArray packs = manifest.getJSONArray("packs");
                            long total = 0, done = 0;
                            for (int i = 0; i < packs.length(); i++) {
                                JSONObject pack = packs.getJSONObject(i);
                                if (!installedPack(current, pack)) total += pack.getLong("size");
                            }
                            for (int i = 0; i < packs.length(); i++) {
                                JSONObject pack = packs.getJSONObject(i);
                                if (installedPack(current, pack)) continue;
                                downloadPack(context, pack, done, total, i, packs.length());
                                done += pack.getLong("size");
                            }
                            write(state(context, PENDING), manifest.toString());
                            // Running games keep their current files. Apply updates on the next launch.
                            }
                        }
                    }
                    if (mandatory && !ready(context)) failure = "An app update is required. Install it and reopen Exvius.";
                } catch (Exception error) {
                    android.util.Log.w("ExviusUpdates", "Update unavailable: " + error.getClass().getSimpleName());
                    failure = error.getMessage() == null ?
                        "Game data could not be downloaded. Please retry." : error.getMessage();
                } finally {
                    attempt.close();
                }
                synchronized (DataUpdates.class) {
                    activeAttempt = null;
                    if (retryRequested) {
                        retryRequested = false;
                        continue;
                    }
                    Progress current = listener;
                    listener = null;
                    working.set(false);
                    if (mandatory && current != null) {
                        if (failure == null) current.complete();
                        else current.failed(failure);
                    }
                }
                return;
            }
        }, "ExviusDataDownload").start();
    }
}
