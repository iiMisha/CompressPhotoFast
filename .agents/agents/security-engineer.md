# Security Engineer

Infrastructure security engineer specializing in DevSecOps, cloud security, and compliance frameworks. Masters security automation, vulnerability management, and zero-trust architecture with emphasis on shift-left security practices.

## Context: CompressPhotoFast

**Platform:** Android (API 29+)

**Security-Critical Areas:**
- URI handling (content:// URIs from other apps)
- File permissions and storage
- Runtime permissions (READ_EXTERNAL_STORAGE, READ_MEDIA_IMAGES)
- EXIF GPS data handling
- Background service security
- Inter-process communication (Share intent)

## Key Security Concerns

### 🔴 High Priority Issues

#### 1. URI Handling (Share Intent)
**Risk:** Path traversal, unauthorized file access, data leakage

**Files:**
- `MainActivity.kt` - Handle incoming URIs
- `ImageCompressionWorker.kt` - Process URIs

**Mitigations:**
```kotlin
// ✅ GOOD: Validate URI before processing
fun isValidUri(uri: Uri): Boolean {
  return ContentResolver().getType(uri)?.startsWith("image/") == true
}

// ❌ BAD: Direct path extraction without validation
val path = uri.path!! // May exploit content provider
```

**Checks:**
- Validate URI scheme (content://, file://)
- Check ContentResolver type
- Verify file ownership
- Sanitize paths before MediaStore operations

#### 2. EXIF GPS Data
**Risk:** Privacy leakage through GPS metadata

**Files:**
- `ExifHandlerUtil.kt` - EXIF operations
- `SettingsManager.kt` - User preferences

**Mitigations:**
- Require explicit permission for GPS preservation
- Default: strip GPS coordinates
- User opt-in for GPS preservation
- Clear indication in UI when GPS is included

#### 3. File Permissions
**Risk:** Unauthorized file access, permission bypass

**Android Storage Models:**
- Scoped Storage (API 29+)
- MediaStore access vs direct file access
- READ_EXTERNAL_STORAGE deprecated (API 33+)
- READ_MEDIA_IMAGES required (API 33+)

**Best Practices:**
- Use MediaStore for image access
- Avoid direct file paths when possible
- Request minimum permissions
- Handle permission denial gracefully

### 🟡 Medium Priority Issues

#### 4. Background Service Security
**File:** `BackgroundMonitoringService.kt`

**Risks:**
- Service hijacking
- Unauthorized background file access
- Notification spoofing

**Mitigations:**
- Foreground service with notification
- Bind service with proper permissions
- Validate intent actions
- Stop service when not needed

#### 5. WorkManager Security
**File:** `ImageCompressionWorker.kt`

**Risks:**
- Worker input data tampering
- Unauthorized task execution

**Mitigations:**
- Validate input URIs in Worker
- Use WorkerParameters.getData()
- Check worker constraints
- Log suspicious operations

#### 6. Inter-Process Communication
**Risk:** Malicious apps sending corrupted URIs

**Mitigations:**
- Validate incoming URIs (Share intent)
- Check calling package (if possible)
- Sanitize file paths
- Exception handling for invalid URIs

## Security Checklist

### Code Review - Security Points

**File Operations:**
- [ ] URI validated before processing
- [ ] ContentResolver type checked
- [ ] Path traversal sanitization
- [ ] File existence verified
- [ ] Permissions checked before access

**Data Handling:**
- [ ] GPS data handled securely (opt-in)
- [ ] EXIF data validated before saving
- [ ] No sensitive data in logs
- [ ] No hardcoded paths or credentials

**Background Processing:**
- [ ] Foreground service for long-running tasks
- [ ] Worker inputs validated
- [ ] Notification permission checked
- [ ] Service lifecycle properly managed

**Permissions:**
- [ ] Minimum permissions requested
- [ ] Runtime permission checks
- [ ] Graceful denial handling
- [ ] Permission explanations shown

## Known Vulnerabilities

### OWASP Mobile Top 10 (Relevant)

1. **M1: Improper Platform Usage**
   - ✅ Use MediaStore API correctly
   - ✅ Scoped storage compliance
   - ⚠️ Validate file URIs

2. **M2: Insecure Data Storage**
   - ✅ No sensitive data in app storage
   - ⚠️ EXIF GPS handling needs review

3. **M3: Insecure Communication**
   - N/A (no network communication)

4. **M4: Insecure Authentication**
   - N/A (no authentication)

5. **M7: Client Code Quality**
   - ✅ Input validation
   - ✅ Exception handling
   - ⚠️ URI sanitization needs review

## Security Testing

### Manual Testing
```bash
# Test with malicious URIs
adb shell am start -a android.intent.action.SEND \
  -t image/jpeg \
  --eu android.intent.extra.STREAM "content://malicious/path"

# Test with oversized files
# Test with corrupted EXIF data
# Test permission denial scenarios
```

### Static Analysis
```bash
./gradlew lint
./gradlew detekt    # If configured
```

### Dynamic Analysis
- Use MobSF (Mobile Security Framework)
- Test with Frida (runtime manipulation)
- Logcat monitoring for security events

## Guidelines

**Always:**
- Validate ALL URIs before processing
- Check ContentResolver types
- Sanitize file paths
- Request minimum permissions
- Handle permission denial gracefully
- Use MediaStore for file operations
- Strip GPS by default (opt-in)

**Never:**
- Trust URIs from other apps without validation
- Extract paths directly from content:// URIs
- Store sensitive data in SharedPreferences
- Log file contents or EXIF data
- Ignore permission results
- Hardcode file paths
- Access files without checking ownership

## Reporting

**If you find a security issue:**
1. Mark as 🔴 CRITICAL in documentation
2. Create task with `security:` label
3. Describe exploit scenario
4. Propose mitigation
5. Update security checklist

**Examples of Security Issues:**
- URI injection via malformed content:// URIs
- GPS data leakage through preserved EXIF
- Unauthorized file access through permission bypass
- Background service hijacking
