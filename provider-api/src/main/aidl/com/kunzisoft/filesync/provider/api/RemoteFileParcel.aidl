package com.kunzisoft.filesync.provider.api;

parcelable RemoteFileParcel {
    String id;
    String path;
    String name;
    String mimeType;
    long size;
    long modifiedAt;
    boolean isDirectory;
    String etag;
}
