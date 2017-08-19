package com.hanihashemi.photopicker.api;

/**
 * Created by kbibek on 2/18/16.
 */

/**
 * Cache Locations for the imported files, thumbnails of images, preview images of videos etc
 */
public interface CacheLocation {
    /**
     * Save files under your application's files directory on the external storage
     * ex: /sdcard/Android/data/your_package/Pictures, /sdcard/Android/data/your_package/Videos
     * <p/>
     * These files will be deleted when your app is uninstalled.
     * These files are internal to the applications, and not typically visible to the user as media.
     */
    int EXTERNAL_STORAGE_APP_DIR = 200;
    /**
     * Save files under your application's cache directory on the external storage
     * ex: /sdcard/Android/data/your_package/cache/
     * <p/>
     * These files will be ones that get deleted first when the device runs low on storage.
     * There is no guarantee when these files will be deleted.
     */
    int EXTERNAL_CACHE_DIR = 300;
    /**
     * Save files under your application's internal storage directory.
     */
    int INTERNAL_APP_DIR = 400;
}
