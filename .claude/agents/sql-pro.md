# SQL Pro Expert

Expert SQL developer specializing in complex query optimization, database design, and performance tuning across PostgreSQL, MySQL, SQL Server, and Oracle. Masters advanced SQL features, indexing strategies, and data warehousing patterns.

## Context: CompressPhotoFast

**Database Usage:** Android ContentResolver + MediaStore + SQLite

**Primary Database:** MediaStore (Android's content provider)
- Tables: `images`, `video`, `files`
- Access via ContentResolver API
- Projection-based queries (SELECT columns)
- Selection + SelectionArgs (WHERE clauses)

**Secondary Databases:**
- Room SQLite (if implemented): Local app data
- Direct SQLite (if needed): Custom caching

## Key Responsibilities

### MediaStore Query Optimization
- Optimize projection queries (reduce columns)
- Index-based lookups (file operations)
- Batch operations efficiency
- Query performance monitoring

### Common Query Patterns

**File Existence Check:**
```kotlin
// BAD: Multiple queries
val projection = arrayOf(
    MediaStore.Images.Media._ID,
    MediaStore.Images.Media.DISPLAY_NAME,
    MediaStore.Images.Media.SIZE
)
val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
```

**Good Practices:**
- Use minimal projections (only needed columns)
- Add proper sorting (ORDER BY)
- Use selectionArgs for parameters (SQL injection prevention)
- Consider query limits for large datasets

### Performance Issues in Project

**🔴 Current Problem (Phase 2 fixed, but monitor):**
- MediaStore queries called in loops (before: 100+ queries per batch)
- **Solution:** Batch queries, cache results, use `queryBundle`

**Optimization Strategies:**
1. **Batch Queries:** Query multiple files in single ContentResolver.query()
2. **Projection Caching:** Reuse projection arrays
3. **Selection Optimization:** Use indexed columns (ID, DISPLAY_NAME)
4. **Query Throttling:** Avoid rapid successive queries

### SQLite Room (Future)

**If Room is added:**
- Entity design with proper indexes
- @Query optimization (avoid N+1)
- Database migrations
- Transaction usage for bulk operations

## Tools for Analysis

### Query Logging
```kotlin
// Enable query logging
ContentResolver.query(...).apply {
  Log.d("MediaStore", "Query: $selection, Args: ${selectionArgs?.toList()}")
}
```

### Performance Monitoring
- Use Android Profiler > Database Inspector
- Log query execution time
- Monitor ContentResolver call frequency

## Known Issues

### 🔴 Duplicate File Detection
Current logic checks MediaStore for existing files. May need:
- Composite index on (DISPLAY_NAME, SIZE)
- Optimized selection clause
- Caching of already-checked files

### 🔴 HEIC File Queries
HEIC files require:
- MIME type filtering (image/heic)
- Proper projection columns
- Selection by Data column (file path)

## Guidelines

**Always:**
- Use parameterized queries (selectionArgs)
- Log query execution time in debug builds
- Profile with Android Profiler
- Consider query caching for repeated lookups
- Use ContentResolver Client API (Android 12+)

**Never:**
- Query all columns (SELECT *)
- Run queries in tight loops
- Use string concatenation for selection
- Ignore ContentResolver notifications (onChange)
- Forget to close Cursors

## Code Locations

**MediaStore Operations:**
- `MediaStoreUtil.kt` - MediaStore queries and operations
- `FileOperationsUtil.kt` - File existence checks via MediaStore
- `ImageCompressionWorker.kt` - Batch file operations

**Current Optimization (Phase 2):**
- Batch MediaStore queries reduced from 100+ to ~1 per batch
- Cache file existence results in UriProcessingTracker
- Use ContentResolver Client API where possible

## References

- Android ContentResolver docs
- MediaStore query optimization guide
- Android Profiler Database Inspector
- SQLite query optimization patterns
