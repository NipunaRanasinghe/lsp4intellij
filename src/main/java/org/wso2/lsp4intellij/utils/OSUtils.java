package org.wso2.lsp4intellij.utils;

import java.util.Locale;

public class OSUtils {

    public static final String WINDOWS = "windows";
    public static final String UNIX = "unix";
    public static final String MAC = "mac";

    private static final String OS = System.getProperty("os.name").toLowerCase(Locale.getDefault());

    /**
     * Returns name of the operating system running. null if not a unsupported operating system.
     *
     * @return operating system
     */
    public static String getOperatingSystem() {
        if (OSUtils.isWindows()) {
            return WINDOWS;
        } else if (OSUtils.isUnix()) {
            return UNIX;
        } else if (OSUtils.isMac()) {
            return MAC;
        }
        return null;
    }

    public static boolean isWindows() {
        return (OS.contains("win"));
    }

    public static boolean isMac() {
        return (OS.contains("mac"));
    }

    public static boolean isUnix() {
        return (OS.contains("nix") || OS.contains("nux") || OS.contains("aix"));
    }
}
