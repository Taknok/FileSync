package com.kunzisoft.filesync.provider.api;

import com.kunzisoft.filesync.provider.api.RemoteFileParcel;
import android.os.ParcelFileDescriptor;

interface IRemoteProvider {
    List<RemoteFileParcel> list(String path);
    RemoteFileParcel stat(String path);
    ParcelFileDescriptor openRead(String path);
    void write(String path, in ParcelFileDescriptor pfd, boolean overwrite);
    void delete(String path);
    void mkdir(String path);
    void move(String source, String target);
    void authenticate();
    void refresh();
}
