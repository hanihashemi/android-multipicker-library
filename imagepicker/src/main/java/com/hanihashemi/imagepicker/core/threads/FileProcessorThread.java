package com.hanihashemi.imagepicker.core.threads;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.hanihashemi.imagepicker.api.CacheLocation;
import com.hanihashemi.imagepicker.api.entity.ChosenImage;
import com.hanihashemi.imagepicker.api.exceptions.PickerException;
import com.hanihashemi.imagepicker.utils.BitmapUtils;
import com.hanihashemi.imagepicker.utils.FileUtils;
import com.hanihashemi.imagepicker.utils.Logger;
import com.hanihashemi.imagepicker.utils.MimeUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

import static com.hanihashemi.imagepicker.utils.StreamHelper.close;
import static com.hanihashemi.imagepicker.utils.StreamHelper.flush;
import static com.hanihashemi.imagepicker.utils.StreamHelper.verifyStream;

/**
 * Created by kbibek on 2/20/16.
 */
public class FileProcessorThread extends Thread {
    final static int THUMBNAIL_BIG = 1;
    final static int THUMBNAIL_SMALL = 2;
    private final static String TAG = FileProcessorThread.class.getSimpleName();
    final List<? extends ChosenImage> files;
    private final int cacheLocation;
    Context context;

    FileProcessorThread(Context context, List<? extends ChosenImage> files, int cacheLocation) {
        this.context = context;
        this.files = files;
        this.cacheLocation = cacheLocation;
    }

    @Override
    public void run() {
        processFiles();
    }

    private void processFiles() {
        for (ChosenImage file : files) {
            try {
                Logger.d(TAG, "processFile: Before: " + file.toString());
                processFile(file);
                postProcess(file);
                file.setSuccess(true);
                Logger.d(TAG, "processFile: Final Path: " + file.toString());
            } catch (PickerException e) {
                e.printStackTrace();
                file.setSuccess(false);
            }
        }
    }

    private void postProcess(ChosenImage file) throws PickerException {
        file.setCreatedAt(Calendar.getInstance().getTime());
        File f = new File(file.getOriginalPath());
        file.setSize(f.length());
        copyFileToFolder(file);
    }

    private void copyFileToFolder(ChosenImage file) throws PickerException {
        Logger.d(TAG, "copyFileToFolder: folder: " + file.getDirectoryType());
        Logger.d(TAG, "copyFileToFolder: extension: " + file.getExtension());
        Logger.d(TAG, "copyFileToFolder: mimeType: " + file.getMimeType());
        Logger.d(TAG, "copyFileToFolder: type: " + file.getType());
        if (file.getType().equals("image")) {
            file.setDirectoryType(Environment.DIRECTORY_PICTURES);
        } else if (file.getType().equals("video")) {
            file.setDirectoryType(Environment.DIRECTORY_MOVIES);
        }
        String outputPath = getTargetLocationToCopy(file);
        Logger.d(TAG, "copyFileToFolder: Out Path: " + outputPath);
        // Check if file is already in the required destination
        if (outputPath.equals(file.getOriginalPath())) {
            return;
        }
        try {
            File inputFile = new File(file.getOriginalPath());
            File copyTo = new File(outputPath);
            FileUtils.copyFile(inputFile, copyTo);
            file.setOriginalPath(copyTo.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
            throw new PickerException(e);
        }
    }

    private void processFile(ChosenImage file) throws PickerException {
        String uri = file.getQueryUri();
        if (uri.startsWith("file://") || uri.startsWith("/")) {
            file = sanitizeUri(file);
            file.setDisplayName(Uri.parse(file.getOriginalPath()).getLastPathSegment());
            file.setMimeType(guessMimeTypeFromUrl(file.getOriginalPath(), file.getType()));
        } else if (uri.startsWith("http")) {
            file = downloadAndSaveFile(file);
        } else if (uri.startsWith("content:")) {
            file = getAbsolutePathIfAvailable(file);
        }
        uri = file.getOriginalPath();
        // Still content:: Try ContentProvider stream import
        if (uri.startsWith("content:")) {
            file = getFromContentProvider(file);
        }
        uri = file.getOriginalPath();
        // Still content:: Try ContentProvider stream import alternate
        if (uri.startsWith("content:")) {
            file = getFromContentProviderAlternate(file);
        }

        // Check for URL Encoded file paths
        try {
            String decodedURL = Uri.parse(Uri.decode(file.getOriginalPath())).toString();
            if (!decodedURL.equals(file.getOriginalPath())) {
                file.setOriginalPath(decodedURL);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // If starts with file: (For some content providers, remove the file prefix)
    private ChosenImage sanitizeUri(ChosenImage file) {
        if (file.getQueryUri().startsWith("file://")) {
            file.setOriginalPath(file.getQueryUri().substring(7));
        }
        return file;
    }

    private ChosenImage getFromContentProviderAlternate(ChosenImage file) throws PickerException {
        BufferedOutputStream outStream = null;
        BufferedInputStream bStream = null;

        try {
            InputStream inputStream = context.getContentResolver()
                    .openInputStream(Uri.parse(file.getOriginalPath()));

            bStream = new BufferedInputStream(inputStream);
            String mimeType = URLConnection.guessContentTypeFromStream(inputStream);

            verifyStream(file.getOriginalPath(), bStream);

            String localFilePath = generateFileName(file);

            outStream = new BufferedOutputStream(new FileOutputStream(localFilePath));
            byte[] buf = new byte[2048];
            int len;
            while ((len = bStream.read(buf)) > 0) {
                outStream.write(buf, 0, len);
            }
            file.setOriginalPath(localFilePath);
            if (file.getMimeType() != null && file.getMimeType().contains("/*")) {
                if (mimeType != null && !mimeType.isEmpty()) {
                    file.setMimeType(mimeType);
                } else {
                    file.setMimeType(guessMimeTypeFromUrl(file.getOriginalPath(), file.getType()));
                }
            }
        } catch (IOException e) {
            throw new PickerException(e);
        } finally {
            flush(outStream);
            close(bStream);
            close(outStream);
        }

        return file;
    }

    private ChosenImage getFromContentProvider(ChosenImage file) throws PickerException {

        BufferedInputStream inputStream = null;
        BufferedOutputStream outStream = null;
        try {
            String localFilePath = generateFileName(file);
            ParcelFileDescriptor parcelFileDescriptor = context
                    .getContentResolver().openFileDescriptor(Uri.parse(file.getOriginalPath()),
                            "r");
            verifyStream(file.getOriginalPath(), parcelFileDescriptor);

            FileDescriptor fileDescriptor = parcelFileDescriptor
                    .getFileDescriptor();

            inputStream = new BufferedInputStream(new FileInputStream(fileDescriptor));
            String mimeType = URLConnection.guessContentTypeFromStream(inputStream);
            BufferedInputStream reader = new BufferedInputStream(inputStream);

            outStream = new BufferedOutputStream(
                    new FileOutputStream(localFilePath));
            byte[] buf = new byte[2048];
            int len;
            while ((len = reader.read(buf)) > 0) {
                outStream.write(buf, 0, len);
            }
            flush(outStream);
            file.setOriginalPath(localFilePath);
            if (file.getMimeType() != null && file.getMimeType().contains("/*")) {
                if (mimeType != null && !mimeType.isEmpty()) {
                    file.setMimeType(mimeType);
                } else {
                    file.setMimeType(guessMimeTypeFromUrl(file.getOriginalPath(), file.getType()));
                }
            }
        } catch (IOException e) {
            throw new PickerException(e);
        } finally {
            flush(outStream);
            close(outStream);
            close(inputStream);
        }
        return file;
    }

    // Try to get a local copy if available

    private ChosenImage getAbsolutePathIfAvailable(ChosenImage file) {
        String[] projection = {MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE};

        // Workaround for various implementations for Google Photos/Picasa
        if (file.getQueryUri().startsWith(
                "content://com.android.gallery3d.provider")) {
            file.setOriginalPath(Uri.parse(file.getQueryUri().replace(
                    "com.android.gallery3d", "com.google.android.gallery3d")).toString());
        } else {
            file.setOriginalPath(file.getQueryUri());
        }

        // Try to see if there's a cached local copy that is available
        if (file.getOriginalPath().startsWith("content://")) {
            try {
                Cursor cursor = context.getContentResolver().query(Uri.parse(file.getOriginalPath()), projection,
                        null, null, null);
                cursor.moveToFirst();
                try {
                    // Samsung Bug
                    if (!file.getOriginalPath().contains("com.sec.android.gallery3d.provider")) {
                        String path = cursor.getString(cursor
                                .getColumnIndexOrThrow(MediaStore.MediaColumns.DATA));
                        Logger.d(TAG, "processFile: Path: " + path);
                        if (path != null) {
                            file.setOriginalPath(path);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    String displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME));
                    if (displayName != null) {
                        file.setDisplayName(displayName);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                String mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE));
                if (mimeType != null) {
                    file.setMimeType(mimeType);
                }
                cursor.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Check if DownloadsDocument in which case, we can get the local copy by using the content provider
        if (file.getOriginalPath().startsWith("content:") && isDownloadsDocument(Uri.parse(file.getOriginalPath()))) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                String[] data = getPathAndMimeType(file);
                if (data[0] != null) {
                    file.setOriginalPath(data[0]);
                }
                if (data[1] != null) {
                    file.setMimeType(data[1]);
                }
            }
        }

        return file;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private String[] getPathAndMimeType(ChosenImage file) {

        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
        Uri uri = Uri.parse(file.getOriginalPath());
        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isDownloadsDocument(uri)) {
                final String id = DocumentsContract.getDocumentId(uri);
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));

                return getDataAndMimeType(contentUri, null, null, file.getType());
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[]{
                        split[1]
                };

                return getDataAndMimeType(contentUri, selection, selectionArgs, file.getType());
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme())) {
            return getDataAndMimeType(uri, null, null, file.getType());
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            String mimeType = guessMimeTypeFromUrl(path, file.getType());
            String[] data = new String[2];
            data[0] = path;
            data[1] = mimeType;
            return data;
        }

        return null;
    }

    private String[] getDataAndMimeType(Uri uri, String selection,
                                        String[] selectionArgs, String type) {
        String[] data = new String[2];
        Cursor cursor = null;
        String[] projection = {MediaStore.MediaColumns.DATA};

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs,
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                String path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA));
                data[0] = path;
                data[1] = guessMimeTypeFromUrl(path, type);
                return data;
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }

    private boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    private boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    private ChosenImage downloadAndSaveFile(ChosenImage file) {
        String localFilePath;
        try {
            URL u = new URL(file.getQueryUri());
            HttpURLConnection urlConnection = (HttpURLConnection) u.openConnection();
            InputStream stream = new BufferedInputStream(urlConnection.getInputStream());
            BufferedInputStream bStream = new BufferedInputStream(stream);

            String mimeType = guessMimeTypeFromUrl(file.getQueryUri(), file.getType());
            if (mimeType == null) {
                mimeType = URLConnection.guessContentTypeFromStream(stream);
            }

            if (mimeType == null && file.getQueryUri().contains(".")) {
                int index = file.getQueryUri().lastIndexOf(".");
                mimeType = file.getType() + "/" + file.getQueryUri().substring(index + 1);
            }

            if (mimeType == null) {
                mimeType = file.getType() + "/*";
            }

            file.setMimeType(mimeType);

            localFilePath = generateFileName(file);

            File localFile = new File(localFilePath);

            FileOutputStream fileOutputStream = new FileOutputStream(localFile);

            byte[] buffer = new byte[2048];
            int len;
            while ((len = bStream.read(buffer)) > 0) {
                fileOutputStream.write(buffer, 0, len);
            }
            fileOutputStream.flush();
            fileOutputStream.close();
            bStream.close();
            file.setOriginalPath(localFilePath);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return file;
    }

    private String getTargetDirectory(String type) throws PickerException {
        String directory;
        switch (cacheLocation) {
            case CacheLocation.EXTERNAL_CACHE_DIR:
                directory = FileUtils.getExternalCacheDir(context);
                break;
            case CacheLocation.INTERNAL_APP_DIR:
                directory = FileUtils.getInternalFileDirectory(context);
                break;
            case CacheLocation.EXTERNAL_STORAGE_APP_DIR:
            default:
                directory = FileUtils.getExternalFilesDir(type, context);
                break;
        }

        return directory;
    }

    // Guess File extension from the file name
    private String guessExtensionFromUrl(String url) {
        try {
            return MimeTypeMap.getFileExtensionFromUrl(url);
        } catch (Exception e) {
            return null;
        }
    }

    // Guess Mime Type from the file extension
    private String guessMimeTypeFromUrl(String url, String type) {
        String mimeType;
        String extension = guessExtensionFromUrl(url);
        if (extension == null || extension.isEmpty()) {
            if (url.contains(".")) {
                int index = url.lastIndexOf(".");
                extension = url.substring(index + 1);
            } else {
                extension = "*";
            }
        }
        if (type.equals("file")) {
            mimeType = MimeUtils.guessMimeTypeFromExtension(extension);
        } else {
            mimeType = type + "/" + extension;
        }
        return mimeType;
    }

    private String getTargetLocationToCopy(ChosenImage file) throws PickerException {
        String fileName = file.getDisplayName();
        if (fileName == null || fileName.isEmpty()) {
            fileName = UUID.randomUUID().toString();
        }
        // If File name already contains an extension, we don't need to guess the extension
        if (!fileName.contains(".")) {
            String extension = file.getFileExtensionFromMimeType();
            if (extension != null && !extension.isEmpty()) {
                fileName += extension;
                file.setExtension(extension);
            }
        }

        String probableFileName = fileName;
        File probableFile = new File(getTargetDirectory(file.getDirectoryType()) + File.separator
                + probableFileName);
        return probableFile.getAbsolutePath();
    }

    private String generateFileName(ChosenImage file) throws PickerException {
        String fileName = file.getDisplayName();
        if (fileName == null || fileName.isEmpty()) {
            fileName = UUID.randomUUID().toString();
        }
        // If File name already contains an extension, we don't need to guess the extension
        if (!fileName.contains(".")) {
            String extension = file.getFileExtensionFromMimeType();
            if (extension != null && !extension.isEmpty()) {
                fileName += extension;
                file.setExtension(extension);
            }
        }

        if (TextUtils.isEmpty(file.getMimeType())) {
            file.setMimeType(guessMimeTypeFromUrl(file.getOriginalPath(), file.getType()));
        }

        String probableFileName = fileName;
        File probableFile = new File(getTargetDirectory(file.getDirectoryType()) + File.separator
                + probableFileName);
        int counter = 0;
        while (probableFile.exists()) {
            counter++;
            if (fileName.contains(".")) {
                int indexOfDot = fileName.lastIndexOf(".");
                probableFileName = fileName.substring(0, indexOfDot - 1) + "-" + counter + "." + fileName.substring(indexOfDot + 1);
            } else {
                probableFileName = fileName + "(" + counter + ")";
            }
            probableFile = new File(getTargetDirectory(file.getDirectoryType()) + File.separator
                    + probableFileName);
        }
        fileName = probableFileName;

        file.setDisplayName(fileName);

        return getTargetDirectory(file.getDirectoryType()) + File.separator
                + fileName;
    }

    Activity getActivityFromContext() {
        return (Activity) context;
    }

    ChosenImage ensureMaxWidthAndHeight(int maxWidth, int maxHeight, ChosenImage image) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BufferedInputStream boundsOnlyStream = new BufferedInputStream(new FileInputStream(image.getOriginalPath()));
            Bitmap bitmap = BitmapFactory.decodeStream(boundsOnlyStream, null, options);
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (boundsOnlyStream != null) {
                boundsOnlyStream.close();
            }

            int imageWidth = options.outWidth;
            int imageHeight = options.outHeight;

            int[] scaledDimension = BitmapUtils.getScaledDimensions(imageWidth, imageHeight, maxWidth, maxHeight);
            if (!(scaledDimension[0] == imageWidth && scaledDimension[1] == imageHeight)) {
                ExifInterface originalExifInterface = new ExifInterface(image.getOriginalPath());
                String originalRotation = originalExifInterface.getAttribute(ExifInterface.TAG_ORIENTATION);
                BufferedInputStream scaledInputStream = new BufferedInputStream(new FileInputStream(image.getOriginalPath()));
                options.inJustDecodeBounds = false;
                bitmap = BitmapFactory.decodeStream(scaledInputStream, null, options);
                scaledInputStream.close();
                if (bitmap != null) {
                    File original = new File(image.getOriginalPath());
                    image.setTempFile(original.getAbsolutePath());
                    File file = new File(
                            (original.getParent() + File.separator + original.getName()
                                    .replace(".", "-resized.")));
                    FileOutputStream stream = new FileOutputStream(file);

                    Matrix matrix = new Matrix();
                    matrix.postScale((float) scaledDimension[0] / imageWidth, (float) scaledDimension[1] / imageHeight);

                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                            bitmap.getHeight(), matrix, false);
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                    image.setOriginalPath(file.getAbsolutePath());
                    ExifInterface resizedExifInterface = new ExifInterface(file.getAbsolutePath());
                    resizedExifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, originalRotation);
                    resizedExifInterface.saveAttributes();
                    image.setWidth(scaledDimension[0]);
                    image.setHeight(scaledDimension[1]);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return image;
    }

    String downScaleAndSaveImage(String image, int scale) throws PickerException {

        FileOutputStream stream = null;
        BufferedInputStream bstream = null;
        Bitmap bitmap;
        try {
            BitmapFactory.Options optionsForGettingDimensions = new BitmapFactory.Options();
            optionsForGettingDimensions.inJustDecodeBounds = true;
            BufferedInputStream boundsOnlyStream = new BufferedInputStream(new FileInputStream(image));
            bitmap = BitmapFactory.decodeStream(boundsOnlyStream, null, optionsForGettingDimensions);
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (boundsOnlyStream != null) {
                boundsOnlyStream.close();
            }
            int w, l;
            w = optionsForGettingDimensions.outWidth;
            l = optionsForGettingDimensions.outHeight;

            ExifInterface exif = new ExifInterface(image);

            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            int rotate = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_270:
                    rotate = -90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    rotate = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    rotate = 90;
                    break;
            }

            int what = w > l ? w : l;

            BitmapFactory.Options options = new BitmapFactory.Options();
            if (what > 3000) {
                options.inSampleSize = scale * 6;
            } else if (what > 2000 && what <= 3000) {
                options.inSampleSize = scale * 5;
            } else if (what > 1500 && what <= 2000) {
                options.inSampleSize = scale * 4;
            } else if (what > 1000 && what <= 1500) {
                options.inSampleSize = scale * 3;
            } else if (what > 400 && what <= 1000) {
                options.inSampleSize = scale * 2;
            } else {
                options.inSampleSize = scale;
            }

            options.inJustDecodeBounds = false;
            // TODO: Sometime the decode File Returns null for some images
            // For such cases, thumbnails can't be created.
            // Thumbnails will link to the original file
            BufferedInputStream scaledInputStream = new BufferedInputStream(new FileInputStream(image));
            bitmap = BitmapFactory.decodeStream(scaledInputStream, null, options);
//            verifyBitmap(fileImage, bitmap);
            scaledInputStream.close();
            if (bitmap != null) {
                File original = new File(URLDecoder.decode(image, Charset.defaultCharset().name()));
                File file = new File(
                        (original.getParent() + File.separator + original.getName()
                                .replace(".", "-scale-" + scale + ".")));
                stream = new FileOutputStream(file);
                if (rotate != 0) {
                    Matrix matrix = new Matrix();
                    matrix.setRotate(rotate);
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                            bitmap.getHeight(), matrix, false);
                }

                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                return file.getAbsolutePath();
            }

        } catch (Exception e) {
            throw new PickerException("Error while generating thumbnail: " + scale + " " + image);
        } finally {
            close(bstream);
            flush(stream);
            close(stream);
        }

        return null;
    }

    String getWidthOfImage(String path) {
        String width = "";
        try {
            ExifInterface exif = new ExifInterface(path);
            width = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH);
            if (width.equals("0")) {
                width = Integer.toString(getBitmapImage(path).get().getWidth());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return width;
    }

    String getHeightOfImage(String path) {
        String height = "";
        try {
            ExifInterface exif = new ExifInterface(path);
            height = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH);
            if (height.equals("0")) {
                height = Integer.toString(getBitmapImage(path).get().getHeight());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return height;
    }

    private SoftReference<Bitmap> getBitmapImage(String path) {
        SoftReference<Bitmap> bitmap;
        bitmap = new SoftReference<>(BitmapFactory.decodeFile(Uri.fromFile(new File(path)).getPath()));
        return bitmap;
    }

    int getOrientation(String image) {
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            ExifInterface exif = new ExifInterface(image);

            orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return orientation;
    }

    float[] getLatLong(String image) {
        try {
            ExifInterface exif = new ExifInterface(image);

            float[] latLong = new float[2];
            if (exif.getLatLong(latLong))
                return latLong;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}