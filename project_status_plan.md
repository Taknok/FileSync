# FileSync Project Status & Roadmap

## 📋 Current Project State
- **Package**: `com.kunzisoft.filesync`
- **Architecture**: Modular (9 Gradle modules), Hilt for DI, Room for metadata, WorkManager for sync.
- **WebDAV Provider**: Fully functional using Retrofit + SimpleXML + Sardine-Android models.
- **SAF Integration**: 
    - Authority: `com.kunzisoft.filesync.documents`
    - Features: Folder navigation, Create file/folder, Rename, and Delete.
    - Path Logic: Deterministic IDs `providerId:path/to/file` with proper parent-child hierarchy.
- **Streaming**: Implemented via `ParcelFileDescriptor` pipes for memory-efficient read/write.
- **Sync Engine**: Per-folder background synchronization with a 30-second throttle to prevent redundant network requests.

To compact this conversation for a new model context, here is the essential technical summary of everything we've built and the critical decisions made:
🏗️ Core Architecture
•
Modular System: 9 Gradle modules (:app, :sync, :storage, :provider-webdav, etc.) using Hilt for DI.
•
SAF Integration: FileSyncDocumentProvider (Authority: com.kunzisoft.filesync.documents) acts as the filesystem bridge.
•
Sync Engine: WorkManager handles background sync per-folder, triggered automatically by SAF browsing with a 30s throttle to prevent network flooding.
•
Security: Credentials stored in EncryptedSharedPreferences (AES-256 via Android Keystore). Database uses Room (Metadata & Config).
🌐 WebDAV Implementation (Hardenend)
•
Stack: Retrofit + SimpleXML + Sardine-Android models for robust protocol parsing.
•
Path Logic: Deterministic documentId format: providerId:path/to/file (relative path, no leading slash).
•
URL Safety: Uses HttpUrl builder to handle special characters (spaces, etc.) and avoid double-slash errors (prevents 409/500 errors).
•
Streaming: Bidirectional data pumping via ParcelFileDescriptor pipes.
•
Empty Files: Specialized write logic forces Content-Length: 0 for new 0-byte files to avoid server-side crashes.
🛠️ Solved Technical Hurdles (CRITICAL)
1.
Deadlock Fix: FileSyncApplication uses a Hilt EntryPoint for the HiltWorkerFactory. This is mandatory because DocumentProvider starts before Application.onCreate finishes, causing lateinit crashes otherwise.
2.
Hierarchy Fix: Implemented explicit parentId in FileMetadata and standardized path normalization in the parser to fix "Double Folder" and "Invisible Files" bugs.
3.
Permission Fix: Implemented isChildDocument and enabled android:grantUriPermissions in the manifest to resolve "No Write Permission" errors in SAF editors.
🚀 Current Capability
•
[x] Full SAF Support: Browse, Create, Open, Rename, Delete.
•
[x] Auto-Refresh: Pull-down or entering folder in SAF triggers background sync.
•
[x] Memory Safe: Streams file content directly from network to client app.
📅 Next Immediate Goal
Phase 1: Conflict Protection: Update FileMetadata to track server ETags and implement a pre-upload check in openDocument(mode="w") to prevent KeePass database overwrites.

---

## 🚀 Roadmap & Next Steps

### Phase 1: Conflict Protection (Database Safety)
*Protecting KeePass databases from accidental overwrites.*
- [x] **DB Update**: Ensure `FileMetadata` strictly tracks the server-side `etag` and `lastModified` timestamp.
- [x] **Pre-Upload Validation**: In `FileSyncDocumentProvider.openDocument(mode="w")`, perform a `stat` call or check ETag before allowing the upload.
- [x] **Conflict Handling**: If the server version is newer than the local cached metadata, block the write and flag the file as "In Conflict".
- [x] **Resolution UI**: Create a simple screen to allow users to "Force Overwrite" or "Keep Both Versions" when a conflict occurs. (Note: "Keep Both" to be improved in UI).
- [x] **Reliable Save Engine**: Implemented `ProxyFileDescriptor` with pre-filled buffers to fix empty-file uploads and data erasure bugs.

### Phase 2: Enhanced Streaming & UX (Stability Overhaul)
*Robust file handling via local caching.*
- [x] **Refactor openDocument**: Transitioned from flaky `ProxyFileDescriptor` callbacks to a robust **Cache-First** strategy.
- [x] **Local Caching**: Files are now fully cached locally before editing, ensuring 100% reliability and zero typing lag.
- [x] **Atomic Uploads**: Uploads now trigger only after the file is fully closed by the editing app, preventing partial saves.
- [x] **Conflict Notifications**: System notifications for blocked saves (completed in Phase 1 polish).

### Phase 3: IPC Bridge (F-Droid Modularity)
*Enabling providers to run as separate apps.*
- [ ] **AIDL Service**: Implement `RemoteProviderService` in the `:provider-webdav` module.
- [ ] **Discovery**: Update `BinderProviderRegistry` to use Android's `PackageManager` to find apps responding to the `org.filesync.PROVIDER` intent.
- [ ] **Security Whitelist**: Add signature verification logic to ensure the core app only communicates with authorized provider plugins.

### Phase 4: UI & Observability
*Improving transparency for the user.*
- [ ] **Sync Dashboard**: Display "Last Synced" time and status (Success/Error) for each folder.
- [ ] **Log Viewer**: Add an in-app screen to view recent network logs for troubleshooting without a PC.
- [ ] **Account Quota**: Display storage usage and availability in the SAF root list.

---

## 🛠 Critical Technical Context for Resume
- **Hilt Initialization**: `FileSyncApplication` uses an `EntryPoint` to manually pull the `HiltWorkerFactory`. This is necessary because `DocumentProvider` starts before `Application.onCreate` finishes, which can cause a `lateinit` initialization crash otherwise.
- **Database Schema**: Current version is **5**. Tables: `providers` (accounts) and `file_metadata` (cache).
- **WebDAV URL Building**: Always use `HttpUrl` (OkHttp) in the provider to handle character encoding and slash normalization.
- **Permissions**: The app requires `INTERNET`, `ACCESS_NETWORK_STATE`, and `MANAGE_DOCUMENTS`.
