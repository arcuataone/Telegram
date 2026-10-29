package org.telegram.messenger.transport;

import android.content.Context;

import java.io.File;

public class MieruBinary {

    private static final String FILE_NAME = "libmieru.so";

    public static File getExecutable(Context context) {
        File nativeDir = new File(
                context.getApplicationInfo().nativeLibraryDir
        );

        return new File(nativeDir, FILE_NAME);
    }
}