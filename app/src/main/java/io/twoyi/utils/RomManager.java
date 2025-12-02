/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

/**
 * @author weishu
 * @date 2021/10/22.
 */

public final class RomManager {

    private static final String TAG = "RomManager";

    private static final String ROOTFS_NAME = "rootfs.tgz";

    private static final String ROM_INFO_FILE = "rom.ini";

    private static final String DEFAULT_INFO = "unknown";

    private static final String LOADER_FILE = "libloader.so";

    private static final String CUSTOM_ROM_FILE_NAME = "rootfs_3rd.tgz";

    private RomManager() {
    }

    public static void initRootfs(Context context) {
        Log.i(TAG, "initRootfs: starting initialization");
        File propFile = getVendorPropFile(context);
        String language = Locale.getDefault().getLanguage();
        String country = Locale.getDefault().getCountry();

        Log.i(TAG, "initRootfs: language=" + language + ", country=" + country);

        Properties properties = new Properties();

        properties.setProperty("persist.sys.language", language);
        properties.setProperty("persist.sys.country", country);

        TimeZone timeZone = TimeZone.getDefault();
        String timeZoneID = timeZone.getID();
        Log.i(TAG, "initRootfs: timezone=" + timeZoneID);
        properties.setProperty("persist.sys.timezone", timeZoneID);

        int density = DisplayMetrics.DENSITY_DEVICE_STABLE;
        Log.i(TAG, "initRootfs: lcd_density=" + density);
        properties.setProperty("ro.sf.lcd_density", String.valueOf(density));

        try (Writer writer = new FileWriter(propFile)) {
            properties.store(writer, null);
            Log.i(TAG, "initRootfs: properties written to " + propFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "initRootfs: failed to write properties", e);
        }
    }

    public static void ensureBootFiles(Context context) {
        Log.i(TAG, "ensureBootFiles: starting");

        // <rootdir>/dev/
        File devDir = new File(getRootfsDir(context), "dev");
        Log.i(TAG, "ensureBootFiles: creating dev directories");
        ensureDir(new File(devDir, "input"));
        ensureDir(new File(devDir, "socket"));
        ensureDir(new File(devDir, "maps"));

        ensureDir(new File(context.getDataDir(), "socket"));

        Log.i(TAG, "ensureBootFiles: creating loader symlink");
        createLoaderSymlink(context);

        Log.i(TAG, "ensureBootFiles: killing orphan processes");
        killOrphanProcess();

        Log.i(TAG, "ensureBootFiles: saving last kmsg");
        saveLastKmsg(context);
        
        Log.i(TAG, "ensureBootFiles: completed");
    }

    private static void createLoaderSymlink(Context context) {
        Path loaderSymlink = new File(context.getDataDir(), "loader64").toPath();
        String loaderPath = getLoaderPath(context);
        try {
            Files.deleteIfExists(loaderSymlink);
            Files.createSymbolicLink(loaderSymlink, Paths.get(loaderPath));
        } catch (IOException e) {
            throw new RuntimeException("symlink loader failed.", e);
        }
    }

    private static void killOrphanProcess() {
        Shell shell = ShellUtil.newSh();
        shell.newJob().add("ps -ef | awk '{if($3==1) print $2}' | xargs kill -9").exec();
    }

    private static void saveLastKmsg(Context context) {
        File lastKmsgFile = LogEvents.getLastKmsgFile(context);
        File kmsgFile = LogEvents.getKmsgFile(context);
        try {
            Files.move(kmsgFile.toPath(), lastKmsgFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    public static class RomInfo {
        public String author = DEFAULT_INFO;
        public String version = DEFAULT_INFO;
        public String desc = DEFAULT_INFO;
        public String md5 = "";
        public long code = 0;

        @Override
        public String toString() {
            return "RomInfo{" +
                    "author='" + author + '\'' +
                    ", version='" + version + '\'' +
                    ", md5='" + md5 + '\'' +
                    ", code=" + code +
                    '}';
        }

        public boolean isValid() {
            return this != DEFAULT_ROM_INFO;
        }
    }

    public static final RomInfo DEFAULT_ROM_INFO = new RomInfo();

    public static boolean romExist(Context context) {
        File rootfsDir = getRootfsDir(context);
        File initFile = new File(rootfsDir, "init");
        // Also check for critical system files to detect corrupted installation
        File appProcess64 = new File(rootfsDir, "system/bin/app_process64");
        File systemBuildProp = new File(rootfsDir, "system/build.prop");
        
        Log.i(TAG, "romExist: checking rootfsDir=" + rootfsDir.getAbsolutePath());
        Log.i(TAG, "romExist: init exists=" + initFile.exists());
        Log.i(TAG, "romExist: app_process64 exists=" + appProcess64.exists());
        Log.i(TAG, "romExist: build.prop exists=" + systemBuildProp.exists());
        
        // ROM exists only if init AND critical system files exist
        boolean exists = initFile.exists() && appProcess64.exists() && systemBuildProp.exists();
        if (initFile.exists() && !exists) {
            Log.w(TAG, "romExist: ROM appears corrupted - init exists but critical files missing. Will re-extract.");
        }
        Log.i(TAG, "romExist: returning " + exists);
        return exists;
    }

    public static boolean needsUpgrade(Context context) {
        RomInfo currentRomInfo = getCurrentRomInfo(context);
        Log.i(TAG, "current rom: " + currentRomInfo);
        if (currentRomInfo.equals(DEFAULT_ROM_INFO)) {
            return true;
        }

        RomInfo romInfoFromAssets = getRomInfoFromAssets(context);
        Log.i(TAG, "asset rom: " + romInfoFromAssets);
        return romInfoFromAssets.code > currentRomInfo.code;
    }

    public static RomInfo getCurrentRomInfo(Context context) {
        File infoFile = new File(getRootfsDir(context), ROM_INFO_FILE);
        try (FileInputStream inputStream = new FileInputStream(infoFile)) {
            return getRomInfo(inputStream);
        } catch (Throwable e) {
            return DEFAULT_ROM_INFO;
        }
    }

    public static String getLoaderPath(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        return new File(applicationInfo.nativeLibraryDir, LOADER_FILE).getAbsolutePath();
    }

    public static RomInfo getRomInfo(File rom) {
        try (FileInputStream fis = new FileInputStream(rom);
             BufferedInputStream bis = new BufferedInputStream(fis);
             GzipCompressorInputStream gzis = new GzipCompressorInputStream(bis);
             TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {

            TarArchiveEntry entry;

            while ((entry = tais.getNextTarEntry()) != null) {
                // Check for rom.ini in either root or rootfs directory
                String entryName = entry.getName();
                if (entryName.equals("rom.ini") || entryName.equals("rootfs/rom.ini")) {
                    byte[] content = new byte[(int) entry.getSize()];
                    int bytesRead = 0;
                    while (bytesRead < content.length) {
                        int read = tais.read(content, bytesRead, content.length - bytesRead);
                        if (read < 0) break;
                        bytesRead += read;
                    }
                    ByteArrayInputStream bais = new ByteArrayInputStream(content);
                    return getRomInfo(bais);
                }
            }
        } catch (Throwable e) {
            LogEvents.trackError(e);
        }
        return DEFAULT_ROM_INFO;
    }

    public static RomInfo getRomInfoFromAssets(Context context) {
        AssetManager assets = context.getAssets();
        try (InputStream open = assets.open(ROM_INFO_FILE)) {
            return getRomInfo(open);
        } catch (Throwable ignored) {
        }
        return DEFAULT_ROM_INFO;
    }

    public static void extractRootfs(Context context, boolean romExist, boolean needsUpgrade, boolean forceInstall, boolean use3rdRom) {
        Log.i(TAG, "extractRootfs: starting extraction, romExist=" + romExist + ", needsUpgrade=" + needsUpgrade + 
              ", forceInstall=" + forceInstall + ", use3rdRom=" + use3rdRom);

        // force remove system dir to avoiding wired issues
        Log.i(TAG, "extractRootfs: removing system partition");
        removeSystemPartition(context);
        Log.i(TAG, "extractRootfs: removing vendor partition");
        removeVendorPartition(context);

        if (!romExist) {
            // first init
            Log.i(TAG, "extractRootfs: ROM does not exist, extracting from assets");
            extractRootfsInAssets(context);
            return;
        }

        if (forceInstall) {
            if (use3rdRom) {
                // install 3rd rom
                Log.i(TAG, "extractRootfs: force install 3rd party ROM");
                boolean success = extract3rdRootfs(context);
                if (!success) {
                    Log.e(TAG, "extractRootfs: 3rd party ROM extraction failed");
                    showRootfsInstallationFailure(context);
                    return;
                }
            } else {
                // factory reset!!
                Log.i(TAG, "extractRootfs: factory reset, extracting from assets");
                if (!extractRootfsInAssets(context)) {
                    Log.e(TAG, "extractRootfs: factory reset extraction failed");
                    showRootfsInstallationFailure(context);
                    return;
                }
            }

            // force install finish, reset the state.
            Log.i(TAG, "extractRootfs: force install completed, resetting flag");
            AppKV.setBooleanConfig(context, AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        } else {
            if (use3rdRom) {
                Log.w(TAG, "extractRootfs: WTF? 3rd ROM must be force install!");
            }
            if (needsUpgrade) {
                Log.i(TAG, "extractRootfs: upgrading factory ROM");
                if (!extractRootfsInAssets(context)) {
                    Log.e(TAG, "extractRootfs: upgrade extraction failed");
                    showRootfsInstallationFailure(context);
                }
            }
        }
        Log.i(TAG, "extractRootfs: completed");
    }

    private static void showRootfsInstallationFailure(Context context) {
        // TODO
    }

    public static void reboot(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        context.getApplicationContext().startActivity(intent);

        shutdown(context);
    }

    public static void shutdown(Context context) {
        System.exit(0);
        Process.killProcess(Process.myPid());
    }

    public static boolean extract3rdRootfs(Context context) {
        File rootfs3rd = get3rdRootfsFile(context);
        if (!rootfs3rd.exists()) {
            return false;
        }
        int err = extractRootfs(context, rootfs3rd);
        return err == 0;
    }

    public static int extractRootfs(Context context, File rootfsTgz) {
        File outputDir = getRootfsDir(context);
        // Ensure the rootfs directory exists
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Log.e(TAG, "Failed to create rootfs directory: " + outputDir);
            return -1;
        }
        try (FileInputStream fis = new FileInputStream(rootfsTgz);
             BufferedInputStream bis = new BufferedInputStream(fis);
             GzipCompressorInputStream gzis = new GzipCompressorInputStream(bis);
             TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {

            TarArchiveEntry entry;
            while ((entry = tais.getNextTarEntry()) != null) {
                File outputFile = new File(outputDir, entry.getName());

                if (entry.isDirectory()) {
                    if (!outputFile.exists() && !outputFile.mkdirs()) {
                        Log.e(TAG, "Failed to create directory: " + outputFile);
                    }
                } else if (entry.isSymbolicLink()) {
                    // Handle symbolic links before regular files
                    File parent = outputFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        Log.e(TAG, "Failed to create parent directory: " + parent);
                    }
                    String linkName = entry.getLinkName();
                    try {
                        Files.deleteIfExists(outputFile.toPath());
                        Files.createSymbolicLink(outputFile.toPath(), Paths.get(linkName));
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to create symlink: " + outputFile + " -> " + linkName, e);
                    }
                } else {
                    // Ensure parent directory exists
                    File parent = outputFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        Log.e(TAG, "Failed to create parent directory: " + parent);
                    }

                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = tais.read(buffer)) != -1) {
                            os.write(buffer, 0, count);
                        }
                    }
                }
            }
            return 0;
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract rootfs", e);
            return -1;
        }
    }

    public static boolean extractRootfsInAssets(Context context) {

        // read assets
        long t1 = SystemClock.elapsedRealtime();
        File rootfsTgz = context.getFileStreamPath(ROOTFS_NAME);
        try (InputStream inputStream = new BufferedInputStream(context.getAssets().open(ROOTFS_NAME));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(rootfsTgz))) {
            byte[] buffer = new byte[10240];
            int count;
            while ((count = inputStream.read(buffer)) > 0) {
                os.write(buffer, 0, count);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        long t2 = SystemClock.elapsedRealtime();

        int ret = extractRootfs(context, rootfsTgz);

        long t3 = SystemClock.elapsedRealtime();

        Log.i(TAG, "extract rootfs, read assets: " + (t2 - t1) + " untgz: " + (t3 - t2) + "ret: " + ret);

        return ret == 0;
    }

    public static File getRootfsDir(Context context) {
        return new File(context.getDataDir(), "rootfs");
    }

    public static File getRomSdcardDir(Context context) {
        return new File(getRootfsDir(context), "sdcard");
    }

    public static File getVendorDir(Context context) {
        return new File(getRootfsDir(context), "vendor");
    }

    public static File getVendorPropFile(Context context) {
        return new File(getVendorDir(context), "default.prop");
    }

    public static File get3rdRootfsFile(Context context) {
        return context.getFileStreamPath(CUSTOM_ROM_FILE_NAME);
    }

    public static boolean isAndroid12() {
        return Build.VERSION.PREVIEW_SDK_INT + Build.VERSION.SDK_INT == Build.VERSION_CODES.S;
    }

    private static void removePartition(Context context, String partition) {
        File rootfsDir = getRootfsDir(context);
        File systemDir = new File(rootfsDir, partition);

        IOUtils.deleteDirectory(systemDir);
    }

    private static void removeSystemPartition(Context context) {
        removePartition(context, "system");
    }

    private static void removeVendorPartition(Context context) {
        removePartition(context, "vendor");
    }

    private static RomInfo getRomInfo(InputStream in) {
        Properties prop = new Properties();
        try {
            prop.load(in);

            RomInfo info = new RomInfo();
            info.author = prop.getProperty("author");
            info.code = Long.parseLong(prop.getProperty("code"));
            info.version = prop.getProperty("version");
            info.desc = prop.getProperty("desc", DEFAULT_INFO);
            info.md5 = prop.getProperty("md5");
            return info;
        } catch (Throwable e) {
            Log.e(TAG, "read rom info err", e);
            return DEFAULT_ROM_INFO;
        }
    }

    private static void ensureDir(File file) {
        if (file.exists()) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        file.mkdirs();
    }
}
